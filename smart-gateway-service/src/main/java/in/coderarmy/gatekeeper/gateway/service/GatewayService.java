package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.dto.GatewayErrorResponse;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.dto.HeaderRule;
import in.coderarmy.gatekeeper.gateway.event.AccessEvent;
import in.coderarmy.gatekeeper.gateway.event.AccessEventPublisher;
import in.coderarmy.gatekeeper.gateway.exception.GatewayPolicyException;
import in.coderarmy.gatekeeper.gateway.model.CompiledRoute;
import in.coderarmy.gatekeeper.gateway.model.ProxyResponse;
import in.coderarmy.gatekeeper.gateway.model.RateLimitResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Request pipeline: match route -> check method -> rate limit -> idempotency -> forward -> return response.
 * Every request (success or rejection) produces one access event for the Analytics Service.
 */
@Service
public class GatewayService {

    /** Headers that belong to a single connection (or are set by the HTTP client) and must not be copied. */
    private static final Set<String> SKIPPED_HEADERS = Set.of("host", "connection", "content-length",
            "transfer-encoding", "keep-alive", "upgrade", "te", "trailer", "expect",
            "proxy-authorization", "proxy-connection");

    private final RouteRegistry routeRegistry;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final IdempotencyService idempotencyService;
    private final ProxyClient proxyClient;
    private final AccessEventPublisher accessEventPublisher;

    public GatewayService(RouteRegistry routeRegistry, ObjectMapper objectMapper,
                          RateLimitService rateLimitService, IdempotencyService idempotencyService,
                          ProxyClient proxyClient, AccessEventPublisher accessEventPublisher) {
        this.routeRegistry = routeRegistry;
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.idempotencyService = idempotencyService;
        this.proxyClient = proxyClient;
        this.accessEventPublisher = accessEventPublisher;
    }

    public ResponseEntity<byte[]> handle(HttpServletRequest request) {
        long startedAt = System.nanoTime();
        String correlationId = correlationId(request);
        GatewayRoute route = null;
        ResponseEntity<byte[]> response;
        String outcome;

        try {
            Optional<CompiledRoute> matchedRoute = routeRegistry.find(request.getRequestURI());
            if(matchedRoute.isEmpty()) {
                throw new GatewayPolicyException(HttpStatus.NOT_FOUND,
                        "No active route matches this request", "ROUTE_NOT_FOUND");
            }
            CompiledRoute compiledRoute = matchedRoute.get();
            route = compiledRoute.getGatewayRoute();

            if(!route.getAllowedMethods().contains(request.getMethod())) {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.ALLOW, String.join(", ", route.getAllowedMethods()));
                return finish(request, route, error(HttpStatus.METHOD_NOT_ALLOWED,
                        "HTTP method is not allowed for this route", correlationId, headers),
                        "METHOD_NOT_ALLOWED", correlationId, startedAt);
            }

            // Rate limiting
            RateLimitResult rateLimit = rateLimitService.check(route, request.getRemoteAddr());
            HttpHeaders policyHeaders = rateLimitHeaders(rateLimit);
            if(!rateLimit.isAllowed()) {
                policyHeaders.set(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimit.getRetryAfterSeconds()));
                return finish(request, route, error(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded",
                        correlationId, policyHeaders), "RATE_LIMITED", correlationId, startedAt);
            }

            byte[] body = request.getInputStream().readAllBytes();
            URI target = targetUri(compiledRoute, request);
            Map<String, List<String>> headers = requestHeaders(request, route, correlationId);

            // Idempotency: POST/PATCH on routes that enable it must carry an Idempotency-Key
            boolean idempotent = route.isIdempotencyEnabled()
                    && ("POST".equals(request.getMethod()) || "PATCH".equals(request.getMethod()));
            ProxyResponse proxied;
            outcome = "FORWARDED";
            if(idempotent) {
                String key = request.getHeader("Idempotency-Key");
                if(key == null || key.isBlank() || key.length() > 200) {
                    throw new GatewayPolicyException(HttpStatus.BAD_REQUEST,
                            "This route requires an Idempotency-Key header", "IDEMPOTENCY_KEY_REQUIRED");
                }
                String redisKey = "gateway:idempotency:" + route.getOrganizationId() + ":" + route.getRouteId()
                        + ":" + request.getRemoteAddr().replace(':', '_') + ":" + key;
                String requestHash = hash(request.getMethod() + " " + target + "\n", body);

                Optional<ProxyResponse> stored = idempotencyService.start(redisKey, requestHash);
                if(stored.isPresent()) {
                    proxied = stored.get();
                    policyHeaders.set("Idempotent-Replayed", "true");
                    outcome = "REPLAYED";
                }
                else {
                    try {
                        proxied = forward(request, route, target, headers, body);
                    }
                    catch(RuntimeException exception) {
                        idempotencyService.release(redisKey);
                        throw exception;
                    }
                    idempotencyService.finish(redisKey, requestHash, proxied);
                }
            }
            else {
                proxied = forward(request, route, target, headers, body);
            }

            response = toResponse(proxied, route, policyHeaders, correlationId);
        }
        catch(GatewayPolicyException exception) {
            outcome = exception.getOutcome();
            response = error(exception.getStatus(), exception.getMessage(), correlationId, null);
        }
        catch(IOException exception) {
            outcome = "BAD_REQUEST";
            response = error(HttpStatus.BAD_REQUEST, "Could not read the request body", correlationId, null);
        }
        return finish(request, route, response, outcome, correlationId, startedAt);
    }

    private ProxyResponse forward(HttpServletRequest request, GatewayRoute route, URI target,
                                  Map<String, List<String>> headers, byte[] body) {
        return proxyClient.send(request.getMethod(), target, headers, body, route.getResponseTimeoutMs());
    }

    /** /foodfast/orders/42?x=1 with pattern /foodfast/orders/** -> targetBaseUrl + /orders + /42?x=1 */
    private URI targetUri(CompiledRoute compiledRoute, HttpServletRequest request) {
        GatewayRoute route = compiledRoute.getGatewayRoute();
        String rest = request.getRequestURI().substring(
                Math.min(compiledRoute.getSourcePathPrefix().length(), request.getRequestURI().length()));
        String base = route.getTargetBaseUrl().replaceAll("/+$", "");
        String prefix = route.getTargetPathPrefix().replaceAll("/+$", "");
        String path = (prefix + rest).isEmpty() ? "/" : prefix + rest;
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        try {
            return URI.create(base + path + query);
        }
        catch(IllegalArgumentException exception) {
            throw new GatewayPolicyException(HttpStatus.BAD_REQUEST, "Invalid request path", "BAD_REQUEST");
        }
    }

    private Map<String, List<String>> requestHeaders(HttpServletRequest request, GatewayRoute route,
                                                     String correlationId) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(String name : Collections.list(request.getHeaderNames())) {
            if(!SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ENGLISH))) {
                headers.put(name, new ArrayList<>(Collections.list(request.getHeaders(name))));
            }
        }
        headers.put("X-Forwarded-For", List.of(request.getRemoteAddr()));
        headers.put("X-Forwarded-Host", List.of(String.valueOf(request.getHeader("Host"))));
        headers.put("X-Correlation-ID", List.of(correlationId));
        applyHeaderRules(headers, route, "REQUEST");
        return headers;
    }

    private ResponseEntity<byte[]> toResponse(ProxyResponse proxied, GatewayRoute route, HttpHeaders policyHeaders,
                                              String correlationId) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        proxied.headers().forEach((name, values) -> {
            if(!SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ENGLISH)) && !name.startsWith(":")) {
                headers.put(name, new ArrayList<>(values));
            }
        });
        applyHeaderRules(headers, route, "RESPONSE");

        HttpHeaders responseHeaders = new HttpHeaders();
        headers.forEach(responseHeaders::addAll);
        responseHeaders.addAll(policyHeaders);
        responseHeaders.set("X-Correlation-ID", correlationId);
        return ResponseEntity.status(proxied.status()).headers(responseHeaders).body(proxied.body());
    }

    private void applyHeaderRules(Map<String, List<String>> headers, GatewayRoute route, String direction) {
        for(HeaderRule rule : route.getHeaderRules()) {
            if(!direction.equals(rule.getDirection())) {
                continue;
            }
            if("REMOVE".equals(rule.getAction())) {
                headers.remove(rule.getHeaderName());
            }
            else if("ADD".equals(rule.getAction())) {
                headers.computeIfAbsent(rule.getHeaderName(), name -> new ArrayList<>()).add(rule.getHeaderValue());
            }
        }
    }

    private ResponseEntity<byte[]> finish(HttpServletRequest request, GatewayRoute route,
                                          ResponseEntity<byte[]> response, String outcome,
                                          String correlationId, long startedAt) {
        accessEventPublisher.publish(new AccessEvent(UUID.randomUUID().toString(), LocalDateTime.now(),
                correlationId, route == null ? null : route.getOrganizationId(),
                route == null ? null : route.getRouteId(), request.getMethod(), request.getRequestURI(),
                response.getStatusCode().value(), (System.nanoTime() - startedAt) / 1_000_000, outcome,
                request.getRemoteAddr()));
        return response;
    }

    private static String hash(String head, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(head.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch(NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");

        // Reject values that could inject extra headers or flood the logs.
        if(correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,100}")) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    private HttpHeaders rateLimitHeaders(RateLimitResult result) {
        HttpHeaders headers = new HttpHeaders();
        if(result.getLimit() > 0) {
            headers.set("X-RateLimit-Limit", String.valueOf(result.getLimit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        }
        return headers;
    }

    private ResponseEntity<byte[]> error(HttpStatus status,
                                         String message,
                                         String correlationId,
                                         HttpHeaders additionalHeaders) {

        GatewayErrorResponse response = new GatewayErrorResponse(status.value(), message,
                correlationId, LocalDateTime.now());

        HttpHeaders headers = additionalHeaders == null ? new HttpHeaders() : additionalHeaders;

        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", correlationId);

        return ResponseEntity.status(status).headers(headers)
                .body(objectMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8));
    }
}
