package in.coderarmy.gatekeeper.management.config;

import in.coderarmy.gatekeeper.management.repository.ManagementUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The Flyway seed creates demo accounts with publicly known passwords. On a public deployment, set
 * PLATFORM_ADMIN_PASSWORD and GATEKEEPER_GATEWAY_PASSWORD and they replace the seeded ones at startup.
 */
@Component
public class SeedPasswordOverride implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedPasswordOverride.class);

    private final ManagementUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String platformAdminPassword;
    private final String gatewayPassword;

    public SeedPasswordOverride(ManagementUserRepository users, PasswordEncoder passwordEncoder,
                                @Value("${PLATFORM_ADMIN_PASSWORD:}") String platformAdminPassword,
                                @Value("${GATEKEEPER_GATEWAY_PASSWORD:}") String gatewayPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.platformAdminPassword = platformAdminPassword;
        this.gatewayPassword = gatewayPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        override("platform-admin", platformAdminPassword);
        override("gateway-service", gatewayPassword);
    }

    private void override(String username, String password) {
        if(password == null || password.isBlank()) {
            return;
        }
        users.findByUsername(username).ifPresent(user -> {
            if(!passwordEncoder.matches(password, user.getPasswordHash())) {
                user.setPasswordHash(passwordEncoder.encode(password));
                user.setUpdatedAt(LocalDateTime.now());
                users.save(user);
                log.info("Password for seeded account '{}' set from environment", username);
            }
        });
    }
}
