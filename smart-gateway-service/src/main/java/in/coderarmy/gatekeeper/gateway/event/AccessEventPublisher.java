package in.coderarmy.gatekeeper.gateway.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Sends one event per request to Kafka for the Analytics Service. Async, so it never slows a request down. */
@Component
public class AccessEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccessEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public AccessEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                @Value("${gateway.kafka.access-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Async
    public void publish(AccessEvent event) {
        try {
            kafkaTemplate.send(topic, String.valueOf(event.organizationId()), objectMapper.writeValueAsString(event));
        }
        catch(RuntimeException exception) {
            log.debug("Access event dropped: {}", exception.getMessage());
        }
    }
}
