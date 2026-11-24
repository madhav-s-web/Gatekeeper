package in.coderarmy.gatekeeper.analytics;

import in.coderarmy.gatekeeper.analytics.document.AccessLog;
import in.coderarmy.gatekeeper.analytics.event.AccessEventConsumer;
import in.coderarmy.gatekeeper.analytics.repository.AccessLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccessEventConsumerTest {

    private final AccessLogRepository repository = mock(AccessLogRepository.class);
    private final AccessEventConsumer consumer = new AccessEventConsumer(repository, JsonMapper.builder().build());

    @Test
    void storesTheEventTheGatewaySends() {
        consumer.accessEvent("""
                {"eventId":"e-1","occurredAt":"2026-11-24T10:00:00","correlationId":"c-1","organizationId":1,
                 "routeId":"orders","method":"GET","path":"/foodfast/orders/1","statusCode":200,"latencyMs":12,
                 "outcome":"FORWARDED","clientIp":"127.0.0.1"}
                """);
        ArgumentCaptor<AccessLog> saved = ArgumentCaptor.forClass(AccessLog.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().routeId()).isEqualTo("orders");
        assertThat(saved.getValue().latencyMs()).isEqualTo(12);
    }

    @Test
    void skipsBadMessages() {
        consumer.accessEvent("not json");
        consumer.accessEvent("{\"routeId\":\"orders\"}");
        verify(repository, never()).save(any());
    }
}
