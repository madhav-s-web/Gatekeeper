package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.exception.GatewayPolicyException;
import in.coderarmy.gatekeeper.gateway.model.ProxyResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Idempotency-Key support (like payment APIs): the first request with a key is forwarded and its response
 * stored in Redis; a retry with the same key gets the stored response instead of running twice.
 * Redis value: {"requestHash": "...", "response": null while in progress}.
 */
@Service
public class IdempotencyService {

    private static final Duration IN_PROGRESS_TTL = Duration.ofMinutes(5);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    record Entry(String requestHash, ProxyResponse response) {
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** @return the stored response to replay, or empty if this request should be forwarded now */
    public Optional<ProxyResponse> start(String key, String requestHash) {
        try {
            String lock = objectMapper.writeValueAsString(new Entry(requestHash, null));
            if(Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, lock, IN_PROGRESS_TTL))) {
                return Optional.empty();
            }
            String saved = redisTemplate.opsForValue().get(key);
            if(saved == null) {
                return start(key, requestHash); // expired in between: try again
            }
            Entry entry = objectMapper.readValue(saved, Entry.class);
            if(!entry.requestHash().equals(requestHash)) {
                throw new GatewayPolicyException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "This Idempotency-Key was already used for a different request", "IDEMPOTENCY_KEY_REUSED");
            }
            if(entry.response() == null) {
                throw new GatewayPolicyException(HttpStatus.CONFLICT,
                        "A request with this Idempotency-Key is still in progress", "IDEMPOTENCY_IN_PROGRESS");
            }
            return Optional.of(entry.response());
        }
        catch(GatewayPolicyException exception) {
            throw exception;
        }
        catch(RuntimeException exception) {
            // Without Redis we cannot promise "only once", so refuse rather than risk a double charge.
            throw new GatewayPolicyException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Idempotency is temporarily unavailable", "POLICY_UNAVAILABLE");
        }
    }

    /** Store the response, or release the key after a 5xx so the client can retry. */
    public void finish(String key, String requestHash, ProxyResponse response) {
        try {
            if(response.status() >= 500) {
                redisTemplate.delete(key);
            }
            else {
                redisTemplate.opsForValue().set(key,
                        objectMapper.writeValueAsString(new Entry(requestHash, response)), COMPLETED_TTL);
            }
        }
        catch(RuntimeException ignored) {
            // The in-progress key expires on its own.
        }
    }

    public void release(String key) {
        try {
            redisTemplate.delete(key);
        }
        catch(RuntimeException ignored) {
            // The in-progress key expires on its own.
        }
    }
}
