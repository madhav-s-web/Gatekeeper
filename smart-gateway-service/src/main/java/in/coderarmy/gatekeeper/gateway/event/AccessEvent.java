package in.coderarmy.gatekeeper.gateway.event;

import java.time.LocalDateTime;

public record AccessEvent(String eventId, LocalDateTime occurredAt, String correlationId, Long organizationId,
                          String routeId, String method, String path, int statusCode, long latencyMs,
                          String outcome, String clientIp) {
}
