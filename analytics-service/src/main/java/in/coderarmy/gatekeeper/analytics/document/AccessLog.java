package in.coderarmy.gatekeeper.analytics.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** One request that went through the gateway. eventId as _id makes duplicate Kafka deliveries harmless. */
@Document("access_logs")
public record AccessLog(
        @Id String eventId,
        @Indexed(expireAfter = "30d") LocalDateTime occurredAt,
        String correlationId,
        @Indexed Long organizationId,
        String routeId,
        String method,
        String path,
        int statusCode,
        long latencyMs,
        String outcome,
        String clientIp) {
}
