package in.coderarmy.gatekeeper.analytics.document;

import org.springframework.data.annotation.Id;

/** Per-route totals, calculated by MongoDB (see AccessLogRepository). */
public record RouteStats(@Id String routeId, long requests, long errors, long rateLimited,
                         double avgLatencyMs, long maxLatencyMs) {
}
