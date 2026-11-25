package in.coderarmy.gatekeeper.management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** On a public deployment the seeded demo password must stop working once PLATFORM_ADMIN_PASSWORD is set. */
@SpringBootTest(properties = {"PLATFORM_ADMIN_PASSWORD=Private-Admin-Pass-1",
        "spring.datasource.url=jdbc:h2:mem:override;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class SeedPasswordOverrideTest {

    @Autowired MockMvc mvc;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void environmentPasswordReplacesTheSeededOne() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"platform-admin\",\"password\":\"platform123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"platform-admin\",\"password\":\"Private-Admin-Pass-1\"}"))
                .andExpect(status().isOk());
    }
}
