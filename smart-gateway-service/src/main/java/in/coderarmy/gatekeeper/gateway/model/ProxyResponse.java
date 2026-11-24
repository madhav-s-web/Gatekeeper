package in.coderarmy.gatekeeper.gateway.model;

import java.util.List;
import java.util.Map;

/** A response from the target service. Also what the idempotency store saves and replays. */
public record ProxyResponse(int status, Map<String, List<String>> headers, byte[] body) {
}
