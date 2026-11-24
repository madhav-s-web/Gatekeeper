package in.coderarmy.gatekeeper.analytics.event;

import in.coderarmy.gatekeeper.analytics.document.AccessLog;
import in.coderarmy.gatekeeper.analytics.repository.AccessLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Gateway access events (Kafka) -> MongoDB. A bad message is skipped so it can't block the topic. */
@Component
public class AccessEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessEventConsumer.class);

    private final AccessLogRepository repository;
    private final ObjectMapper objectMapper;

    public AccessEventConsumer(AccessLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${gateway.kafka.access-topic}")
    public void accessEvent(String message) {
        try {
            AccessLog accessLog = objectMapper.readValue(message, AccessLog.class);
            if(accessLog.eventId() == null || accessLog.occurredAt() == null) {
                throw new IllegalArgumentException("eventId and occurredAt are required");
            }
            repository.save(accessLog);
        }
        catch(RuntimeException exception) {
            log.warn("Skipping invalid access event: {}", exception.getMessage());
        }
    }
}
