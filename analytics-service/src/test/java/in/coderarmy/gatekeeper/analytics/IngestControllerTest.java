package in.coderarmy.gatekeeper.analytics;

import in.coderarmy.gatekeeper.analytics.config.SecurityConfig;
import in.coderarmy.gatekeeper.analytics.controller.IngestController;
import in.coderarmy.gatekeeper.analytics.event.AccessEventConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestController.class)
@Import(SecurityConfig.class)
class IngestControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccessEventConsumer consumer;

    @Test
    void acceptsEventsOnlyWithTheSharedToken() throws Exception {
        when(consumer.save(anyString())).thenReturn(true);

        mvc.perform(post("/api/internal/access-events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/access-events").header("X-Ingest-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/access-events").header("X-Ingest-Token", "local-ingest-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        verify(consumer, times(1)).save(anyString());
    }
}
