package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.client.ManagementApiClient;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** A gateway that wakes up must load its routes before answering, not reply 404 to the first request. */
class GatewayConfigurationLoaderTest {

    private final ManagementApiClient client = mock(ManagementApiClient.class);
    private final RouteRegistry registry = new RouteRegistry();
    private final GatewayConfigurationLoader loader = new GatewayConfigurationLoader(client, registry);

    private static GatewayRoute route() {
        GatewayRoute route = new GatewayRoute();
        route.setOrganizationId(1L);
        route.setRouteId("orders");
        route.setPathPattern("/foodfast/orders/**");
        route.setAllowedMethods(Set.of("GET"));
        return route;
    }

    @Test
    void firstRequestLoadsRoutesThenNoMoreReloads() {
        when(client.fetchActiveRoutes()).thenReturn(List.of(route()));

        loader.ensureLoaded();
        assertThat(registry.find("/foodfast/orders/42")).isPresent();

        loader.ensureLoaded();
        loader.ensureLoaded();
        verify(client, times(1)).fetchActiveRoutes();
    }

    @Test
    void failedLoadIsNotRetriedOnEveryRequest() {
        when(client.fetchActiveRoutes()).thenThrow(new IllegalStateException("management is down"));

        loader.ensureLoaded();
        loader.ensureLoaded();
        loader.ensureLoaded();

        verify(client, times(1)).fetchActiveRoutes();
        assertThat(registry.size()).isZero();
    }
}
