package in.coderarmy.gatekeeper.analytics;

import in.coderarmy.gatekeeper.analytics.repository.AccessLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real application context, so wiring problems (like a disabled MongoDB auto-configuration)
 * fail here instead of at runtime. MongoDB itself is not needed: the client connects lazily.
 */
@SpringBootTest(properties = {
        "spring.data.mongodb.auto-index-creation=false",
        "spring.kafka.listener.auto-startup=false"})
class AnalyticsServiceApplicationTests {

	@Autowired
	AccessLogRepository repository;

	@Test
	void contextLoadsWithMongoRepository() {
		assertThat(repository).isNotNull();
	}
}
