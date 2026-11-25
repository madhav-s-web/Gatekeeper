package in.coderarmy.gatekeeper.analytics.controller;

import in.coderarmy.gatekeeper.analytics.event.AccessEventConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** HTTP alternative to Kafka for receiving gateway access events (used where Kafka isn't available). */
@RestController
public class IngestController {

    private final AccessEventConsumer consumer;
    private final byte[] ingestToken;

    public IngestController(AccessEventConsumer consumer, @Value("${analytics.ingest-token}") String ingestToken) {
        this.consumer = consumer;
        this.ingestToken = ingestToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/internal/access-events")
    public ResponseEntity<Void> ingest(@RequestHeader(name = "X-Ingest-Token", required = false) String token,
                                       @RequestBody String event) {
        // Constant-time comparison, so the token can't be guessed from response timing.
        if(token == null || !MessageDigest.isEqual(ingestToken, token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return consumer.save(event) ? ResponseEntity.accepted().build() : ResponseEntity.badRequest().build();
    }
}
