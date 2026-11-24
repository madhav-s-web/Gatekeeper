package in.coderarmy.gatekeeper.analytics;

import in.coderarmy.gatekeeper.analytics.config.SecurityConfig;
import in.coderarmy.gatekeeper.analytics.controller.AnalyticsController;
import in.coderarmy.gatekeeper.analytics.document.RouteStats;
import in.coderarmy.gatekeeper.analytics.repository.AccessLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyticsController.class)
@Import(SecurityConfig.class)
class AnalyticsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccessLogRepository repository;

    static RequestPostProcessor user(String role, Long organizationId) {
        return jwt().jwt(token -> {
            token.claim("role", role);
            if(organizationId != null) token.claim("organizationId", organizationId);
        }).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void tenantSummaryUsesTheOrganizationFromTheToken() throws Exception {
        when(repository.routeStats(eq(7L), any())).thenReturn(List.of(
                new RouteStats("orders", 90, 9, 2, 20.0, 300),
                new RouteStats("menu", 10, 1, 0, 10.0, 50)));

        mvc.perform(get("/api/tenant/analytics/summary").with(user("TENANT_ADMIN", 7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").value(100))
                .andExpect(jsonPath("$.errors").value(10))
                .andExpect(jsonPath("$.avgLatencyMs").value(19.0))
                .andExpect(jsonPath("$.routes[0].routeId").value("orders"));
    }

    @Test
    void rolesAreEnforced() throws Exception {
        when(repository.routeStats(any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/tenant/analytics/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tenant/analytics/summary").with(user("GATEWAY_SERVICE", null))).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/analytics/organizations/7/summary").with(user("TENANT_ADMIN", 7L)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/analytics/organizations/7/summary").with(user("PLATFORM_ADMIN", null)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenant/analytics/summary?hours=0").with(user("TENANT_ADMIN", 7L)))
                .andExpect(status().isBadRequest());
    }
}
