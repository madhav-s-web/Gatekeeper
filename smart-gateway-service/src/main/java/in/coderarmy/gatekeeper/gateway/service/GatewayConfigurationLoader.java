package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.client.ManagementApiClient;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads active routes from API Management at startup, every 30 seconds, and whenever a Kafka event arrives.
 * If API Management is down, the gateway keeps serving the routes it already has instead of crashing.
 */
@Component
public class GatewayConfigurationLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigurationLoader.class);

    private final ManagementApiClient managementApiClient;
    private final RouteRegistry routeRegistry;
    private volatile boolean loaded;
    private volatile long lastFailureAt;

    public GatewayConfigurationLoader(ManagementApiClient managementApiClient,
                                      RouteRegistry routeRegistry) {

        this.managementApiClient = managementApiClient;
        this.routeRegistry = routeRegistry;
    }

    @Override
    public void run(ApplicationArguments arguments) {
       refresh();
    }

    /**
     * Called before routing a request. Right after a cold start (e.g. a serverless instance waking up)
     * the web server can accept a request before the startup load has finished; this makes that request
     * wait for the routes instead of getting a misleading 404. After the first successful load it is a no-op.
     */
    public void ensureLoaded() {
        if(loaded) {
            return;
        }
        synchronized(this) {
            // Concurrent requests share one load; after a failure, retry at most every 5 seconds.
            if(!loaded && System.currentTimeMillis() - lastFailureAt > 5000) {
                refresh();
            }
        }
    }

    @Scheduled(fixedDelayString = "${gateway.refresh-interval-ms:30000}", initialDelayString = "${gateway.refresh-interval-ms:30000}")
    public synchronized void refresh() {
        try {
            List<GatewayRoute> routes = managementApiClient.fetchActiveRoutes();
            routeRegistry.replaceAll(routes);
            loaded = true;
        }
        catch(RuntimeException exception) {
            lastFailureAt = System.currentTimeMillis();
            log.warn("Could not refresh routes, keeping the {} routes already loaded: {}",
                    routeRegistry.size(), String.valueOf(exception));
        }
    }
}
