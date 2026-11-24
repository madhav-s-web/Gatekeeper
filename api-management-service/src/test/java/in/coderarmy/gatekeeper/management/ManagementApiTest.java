package in.coderarmy.gatekeeper.management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs the real app on H2 with the course's Flyway migrations (including the seeded demo accounts). */
@SpringBootTest
@AutoConfigureMockMvc
class ManagementApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate; // no broker in tests

    @Test
    void platformAdminSetsUpTenantAndTenantManagesRoutes() throws Exception {
        String platform = login("platform-admin", "platform123");
        String slug = "shop-" + UUID.randomUUID().toString().substring(0, 6);

        // Platform admin: organization -> ACTIVE -> tenant admin
        JsonNode org = body(mvc.perform(auth(post("/api/platform/organizations"), platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + slug + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
        long orgId = org.get("id").asLong();
        mvc.perform(auth(patch("/api/platform/organizations/" + orgId + "/status"), platform)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(post("/api/platform/organizations/" + orgId + "/tenant-admin"), platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + slug + "-admin\",\"password\":\"secret123\",\"enabled\":true}"))
                .andExpect(status().isCreated());

        // Tenant admin: create a route
        String tenant = login(slug + "-admin", "secret123");
        JsonNode route = body(mvc.perform(auth(post("/api/tenant/routes"), tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(route("orders", "/" + slug + "/orders/**", "")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.configurationVersion").value(1)));
        long routeId = route.get("id").asLong();

        // Rules that protect the gateway
        expect(tenant, route("bad", "/" + slug + "/a/**/b", ""), 400);          // gateway could not parse it
        expect(tenant, route("dup", "/" + slug + "/orders/**", ""), 409);        // same path twice
        expect(tenant, route("other", "/someone-else/x/**", ""), 400);           // other tenant's prefix
        expect(tenant, route("inj", "/" + slug + "/inj/**",
                "{\"direction\":\"REQUEST\",\"action\":\"ADD\",\"headerName\":\"X-A\",\"headerValue\":\"a\\r\\nX-Evil: 1\"}"), 400);
        expect(tenant, route("host", "/" + slug + "/host/**",
                "{\"direction\":\"REQUEST\",\"action\":\"ADD\",\"headerName\":\"Host\",\"headerValue\":\"evil\"}"), 400);
        expect(tenant, "{not json", 400);

        // Activate: new version; activating again changes nothing
        mvc.perform(auth(patch("/api/tenant/routes/" + routeId + "/status"), tenant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(jsonPath("$.configurationVersion").value(2));
        mvc.perform(auth(patch("/api/tenant/routes/" + routeId + "/status"), tenant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(jsonPath("$.configurationVersion").value(2));

        // Gateway account sees the active route
        String gateway = login("gateway-service", "gateway123");
        mvc.perform(auth(get("/api/gateway/routes"), gateway))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.routeId == 'orders' && @.organizationId == " + orgId + ")]").exists());

        // Roles are enforced
        mvc.perform(auth(get("/api/platform/organizations"), tenant)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/tenant/routes"), platform)).andExpect(status().isForbidden());
        mvc.perform(get("/api/tenant/routes")).andExpect(status().isUnauthorized());

        // Delete then re-create: version history continues instead of colliding
        mvc.perform(auth(delete("/api/tenant/routes/" + routeId), tenant)).andExpect(status().isNoContent());
        mvc.perform(auth(post("/api/tenant/routes"), tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(route("orders", "/" + slug + "/orders/**", "")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.configurationVersion").value(4));
        mvc.perform(auth(get("/api/tenant/route-versions/orders"), tenant))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[1].changeType").value("DELETED"));

        // Every change is audited
        mvc.perform(auth(get("/api/tenant/audit-records"), tenant))
                .andExpect(jsonPath("$[0].action").value("CREATE_ROUTE"));
    }

    @Test
    void loginFailuresAndDashboardAreHandled() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"platform-admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.dbStatus").value("UP"));
    }

    // ---------------------------------------------------------------- helpers

    private String login(String username, String password) throws Exception {
        return body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())).get("token").asString();
    }

    private void expect(String token, String routeJson, int status) throws Exception {
        mvc.perform(auth(post("/api/tenant/routes"), token).contentType(MediaType.APPLICATION_JSON).content(routeJson))
                .andExpect(status().is(status));
    }

    private static String route(String routeId, String path, String headerRule) {
        return "{\"routeId\":\"" + routeId + "\",\"name\":\"Orders\",\"pathPattern\":\"" + path + "\","
                + "\"targetBaseUrl\":\"http://localhost:8093\",\"targetPathPrefix\":\"/orders\","
                + "\"requestsPerMinute\":100,\"responseTimeoutMs\":3000,\"idempotencyEnabled\":true,\"active\":false,"
                + "\"allowedMethods\":[\"GET\",\"POST\"],\"headerRules\":[" + headerRule + "]}";
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
