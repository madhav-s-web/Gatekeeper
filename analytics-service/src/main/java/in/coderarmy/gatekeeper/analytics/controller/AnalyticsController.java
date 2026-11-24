package in.coderarmy.gatekeeper.analytics.controller;

import in.coderarmy.gatekeeper.analytics.document.AccessLog;
import in.coderarmy.gatekeeper.analytics.document.RouteStats;
import in.coderarmy.gatekeeper.analytics.repository.AccessLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tenant admins see their own organization (from the "organizationId" claim in their token);
 * platform admins can look at any organization.
 */
@RestController
public class AnalyticsController {

    public record Summary(int hours, long requests, long errors, long rateLimited, double avgLatencyMs,
                          List<RouteStats> routes) {
    }

    private final AccessLogRepository repository;

    public AnalyticsController(AccessLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/tenant/analytics/summary")
    public Summary tenantSummary(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "24") int hours) {
        return summary(organizationId(jwt), hours);
    }

    @GetMapping("/api/tenant/analytics/events")
    public List<AccessLog> tenantEvents(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
        return repository.findByOrganizationIdOrderByOccurredAtDesc(organizationId(jwt),
                PageRequest.of(0, Math.clamp(limit, 1, 200)));
    }

    @GetMapping("/api/platform/analytics/organizations/{organizationId}/summary")
    public Summary platformSummary(@PathVariable Long organizationId, @RequestParam(defaultValue = "24") int hours) {
        return summary(organizationId, hours);
    }

    private Summary summary(Long organizationId, int hours) {
        if(hours < 1 || hours > 24 * 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hours must be between 1 and 720");
        }
        List<RouteStats> routes = repository.routeStats(organizationId, LocalDateTime.now().minusHours(hours));
        long requests = routes.stream().mapToLong(RouteStats::requests).sum();
        double avgLatency = requests == 0 ? 0
                : routes.stream().mapToDouble(route -> route.avgLatencyMs() * route.requests()).sum() / requests;
        return new Summary(hours, requests,
                routes.stream().mapToLong(RouteStats::errors).sum(),
                routes.stream().mapToLong(RouteStats::rateLimited).sum(),
                Math.round(avgLatency * 10) / 10.0, routes);
    }

    private static Long organizationId(Jwt jwt) {
        Number organizationId = jwt.getClaim("organizationId");
        if(organizationId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant identity is missing");
        }
        return organizationId.longValue();
    }
}
