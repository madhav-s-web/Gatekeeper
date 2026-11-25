package in.coderarmy.gatekeeper.gateway.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends one event per request to the Analytics Service. Async, so it never slows a request down.
 * With gateway.analytics.url set (e.g. on Vercel, where nothing can listen to Kafka in the background)
 * events are POSTed over HTTP; otherwise they go to Kafka.
 */
@Component
public class AccessEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccessEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final boolean kafkaEnabled;
    private final String analyticsUrl;
    private final String ingestToken;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public AccessEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                @Value("${gateway.kafka.access-topic}") String topic,
                                @Value("${gateway.kafka.enabled:true}") boolean kafkaEnabled,
                                @Value("${gateway.analytics.url:}") String analyticsUrl,
                                @Value("${gateway.analytics.ingest-token:}") String ingestToken) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.kafkaEnabled = kafkaEnabled;
        this.analyticsUrl = analyticsUrl.replaceAll("/+$", "");
        this.ingestToken = ingestToken;
    }

    @Async
    public void publish(AccessEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            if(!analyticsUrl.isEmpty()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(analyticsUrl + "/api/internal/access-events"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .header("X-Ingest-Token", ingestToken)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            }
            else if(kafkaEnabled) {
                kafkaTemplate.send(topic, String.valueOf(event.organizationId()), json);
            }
        }
        catch(Exception exception) {
            log.debug("Access event dropped: {}", exception.getMessage());
        }
    }
}
