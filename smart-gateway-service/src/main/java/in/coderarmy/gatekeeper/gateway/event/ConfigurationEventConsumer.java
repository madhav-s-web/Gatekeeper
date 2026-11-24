package in.coderarmy.gatekeeper.gateway.event;

import in.coderarmy.gatekeeper.gateway.service.GatewayConfigurationLoader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationEventConsumer {

    private final GatewayConfigurationLoader configurationLoader;

    public ConfigurationEventConsumer(GatewayConfigurationLoader configurationLoader) {
        this.configurationLoader = configurationLoader;
    }

    // A unique group per gateway instance, so EVERY instance hears every change (a shared group would
    // deliver each event to only one instance). "latest": old events don't matter, a refresh loads everything.
    @KafkaListener(topics = "${gateway.kafka.configuration-topic}",
            groupId = "${gateway.kafka.configuration-group}-#{T(java.util.UUID).randomUUID().toString()}",
            properties = "auto.offset.reset=latest")
    public void configurationChanged(String event) {
        configurationLoader.refresh();
    }
}
