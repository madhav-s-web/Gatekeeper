package in.coderarmy.gatekeeper.analytics.repository;

import in.coderarmy.gatekeeper.analytics.document.AccessLog;
import in.coderarmy.gatekeeper.analytics.document.RouteStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AccessLogRepository extends MongoRepository<AccessLog, String> {

    List<AccessLog> findByOrganizationIdOrderByOccurredAtDesc(Long organizationId, Pageable pageable);

    @Aggregation(pipeline = {
            "{ $match: { organizationId: ?0, occurredAt: { $gte: ?1 } } }",
            "{ $group: { _id: '$routeId', requests: { $sum: 1 },"
                    + " errors: { $sum: { $cond: [ { $gte: ['$statusCode', 500] }, 1, 0 ] } },"
                    + " rateLimited: { $sum: { $cond: [ { $eq: ['$outcome', 'RATE_LIMITED'] }, 1, 0 ] } },"
                    + " avgLatencyMs: { $avg: '$latencyMs' }, maxLatencyMs: { $max: '$latencyMs' } } }",
            "{ $sort: { requests: -1 } }"
    })
    List<RouteStats> routeStats(Long organizationId, LocalDateTime since);
}
