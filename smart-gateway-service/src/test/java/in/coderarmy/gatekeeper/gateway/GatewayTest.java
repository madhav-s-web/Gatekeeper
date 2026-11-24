package in.coderarmy.gatekeeper.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.coderarmy.gatekeeper.gateway.service.GatewayConfigurationLoader;
import in.coderarmy.gatekeeper.gateway.service.RouteRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The real gateway between a fake API Management service and a fake target ("orders") service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "gateway.refresh-interval-ms=3600000"})
class GatewayTest {

    static final HttpServer TARGET = server(GatewayTest::target);
    static final HttpServer MANAGEMENT = server(GatewayTest::management);
    static final AtomicInteger TARGET_CALLS = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("management.api.base-url", () -> "http://127.0.0.1:" + MANAGEMENT.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        TARGET.stop(0);
        MANAGEMENT.stop(0);
    }

    @LocalServerPort int port;
    @Autowired GatewayConfigurationLoader loader;
    @Autowired RouteRegistry registry;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean KafkaTemplate<String, String> kafka;

    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> redisValues = new ConcurrentHashMap<>();
    final Map<String, AtomicLong> redisCounters = new ConcurrentHashMap<>();

    /** A tiny in-memory Redis, so the rate-limit and idempotency logic run for real. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void fakeRedisAndLoadRoutes() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(i -> redisValues.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        when(values.get(anyString())).thenAnswer(i -> redisValues.get(i.getArgument(0)));
        doAnswer(i -> redisValues.put(i.getArgument(0), i.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(i -> redisValues.remove(i.<String>getArgument(0)) != null);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(i -> {
            String key = i.<List<String>>getArgument(1).get(0);
            return redisCounters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet() + ":60";
        });

        loader.refresh();
        assertThat(registry.size()).isEqualTo(4);
    }

    @Test
    void forwardsToTargetWithPrefixQueryAndHeaderRules() throws Exception {
        HttpResponse<String> response = send(get("/foodfast/orders/42?expand=true").header("X-Correlation-ID", "abc-1"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"path\":\"/orders/42?expand=true\"");     // prefix rewritten
        assertThat(response.body()).contains("\"x-gateway\":\"gatekeeper\"");            // REQUEST ADD rule
        assertThat(response.headers().firstValue("X-Powered-By")).isEmpty();            // RESPONSE REMOVE rule
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains("abc-1");
    }

    @Test
    void unknownPathWrongMethodAndRateLimit() throws Exception {
        assertThat(send(get("/nobody/here")).statusCode()).isEqualTo(404);

        HttpResponse<String> wrongMethod = send(request("/foodfast/orders/1").DELETE());
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
        assertThat(wrongMethod.headers().firstValue("Allow")).isPresent();

        // "menu" allows 2 requests per minute
        assertThat(send(get("/foodfast/menu")).statusCode()).isEqualTo(200);
        assertThat(send(get("/foodfast/menu")).statusCode()).isEqualTo(200);
        HttpResponse<String> limited = send(get("/foodfast/menu"));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void idempotentPostRunsOnceAndIsReplayed() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(send(post("/foodfast/orders", "{\"item\":\"pizza\"}")).statusCode()).isEqualTo(400); // key required

        int before = TARGET_CALLS.get();
        HttpResponse<String> first = send(post("/foodfast/orders", "{\"item\":\"pizza\"}").header("Idempotency-Key", key));
        HttpResponse<String> retry = send(post("/foodfast/orders", "{\"item\":\"pizza\"}").header("Idempotency-Key", key));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(TARGET_CALLS.get() - before).isEqualTo(1);                        // the order was placed once

        HttpResponse<String> reused = send(post("/foodfast/orders", "{\"item\":\"burger\"}").header("Idempotency-Key", key));
        assertThat(reused.statusCode()).isEqualTo(422);
    }

    @Test
    void targetProblemsBecome502And504() throws Exception {
        assertThat(send(get("/foodfast/slow")).statusCode()).isEqualTo(504);
        assertThat(send(get("/foodfast/down")).statusCode()).isEqualTo(502);
    }

    // ---------------------------------------------------------------- fakes

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpRequest.Builder get(String path) {
        return request(path).GET();
    }

    private HttpRequest.Builder post(String path, String body) {
        return request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String route(String routeId, String path, String target, Integer rpm, int timeoutMs, boolean idempotent,
                        String methods, String headerRules) {
        return """
                {"id":1,"organizationId":1,"routeId":"%s","name":"%s","pathPattern":"%s","targetBaseUrl":"%s",
                 "targetPathPrefix":"/orders","requestsPerMinute":%s,"responseTimeoutMs":%d,"idempotencyEnabled":%s,
                 "active":true,"configurationVersion":1,"allowedMethods":[%s],"headerRules":[%s]}
                """.formatted(routeId, routeId, path, target, rpm, timeoutMs, idempotent, methods, headerRules);
    }

    static void management(HttpExchange exchange) throws IOException {
        String target = "http://127.0.0.1:" + TARGET.getAddress().getPort();
        if(exchange.getRequestURI().getPath().equals("/api/auth/login")) {
            respond(exchange, 200, "{\"token\":\"t\",\"tokenType\":\"Bearer\",\"expiresInSeconds\":3600,\"role\":\"GATEWAY_SERVICE\"}");
        }
        else {
            respond(exchange, 200, "[" + String.join(",",
                    route("orders", "/foodfast/orders/**", target, null, 2000, true, "\"GET\",\"POST\"",
                            "{\"direction\":\"REQUEST\",\"action\":\"ADD\",\"headerName\":\"X-Gateway\",\"headerValue\":\"gatekeeper\"},"
                                    + "{\"direction\":\"RESPONSE\",\"action\":\"REMOVE\",\"headerName\":\"X-Powered-By\"}"),
                    route("menu", "/foodfast/menu", target, 2, 2000, false, "\"GET\"", ""),
                    route("slow", "/foodfast/slow", target + "/slow", null, 300, false, "\"GET\"", ""),
                    route("down", "/foodfast/down", "http://127.0.0.1:1", null, 1000, false, "\"GET\"", "")) + "]");
        }
    }

    static void target(HttpExchange exchange) throws IOException {
        TARGET_CALLS.incrementAndGet();
        if(exchange.getRequestURI().getPath().startsWith("/slow")) {
            try { Thread.sleep(1500); } catch(InterruptedException ignored) { }
        }
        String gatewayHeader = exchange.getRequestHeaders().getFirst("X-Gateway");
        exchange.getResponseHeaders().add("X-Powered-By", "fake-target");
        respond(exchange, "POST".equals(exchange.getRequestMethod()) ? 201 : 200,
                "{\"call\":" + TARGET_CALLS.get() + ",\"path\":\"" + exchange.getRequestURI() + "\",\"x-gateway\":\""
                        + gatewayHeader + "\"}");
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static HttpServer server(com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        }
        catch(IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
