package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.exception.GatewayPolicyException;
import in.coderarmy.gatekeeper.gateway.model.ProxyResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Sends the request to the target service and turns network problems into 502/504. */
@Component
public class ProxyClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public ProxyResponse send(String method, URI target, Map<String, List<String>> headers, byte[] body,
                              int timeoutMs) {
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(timeoutMs))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));

        try {
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new ProxyResponse(response.statusCode(), response.headers().map(), response.body());
        }
        catch(HttpTimeoutException exception) {
            throw new GatewayPolicyException(HttpStatus.GATEWAY_TIMEOUT,
                    "The target service did not respond in time", "TARGET_TIMEOUT");
        }
        catch(IOException exception) {
            throw new GatewayPolicyException(HttpStatus.BAD_GATEWAY,
                    "The target service is unreachable", "TARGET_UNAVAILABLE");
        }
        catch(InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GatewayPolicyException(HttpStatus.SERVICE_UNAVAILABLE, "Gateway is shutting down", "INTERRUPTED");
        }
    }
}
