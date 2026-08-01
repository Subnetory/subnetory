package dev.subnetory.config;

import dev.subnetory.domain.User;
import dev.subnetory.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

/**
 * Initialise les données runtime nécessaires au premier démarrage.
 * Initialise le mot de passe administrateur si aucun mot de passe n'existe encore.
 * La valeur de bootstrap n'est obligatoire que pour cette première initialisation.
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${subnetory.admin.default-password:}")
    private String defaultAdminPassword;

    @Bean
    ApplicationRunner initAdminPassword(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> userRepository.findByUsername("admin").ifPresent(admin -> {
            if (admin.getPassword() == null || admin.getPassword().isBlank()) {
                if (!StringUtils.hasText(defaultAdminPassword)) {
                    throw new IllegalStateException(
                            "Admin password is not initialized: configure "
                                    + "subnetory.admin.default-password for the first startup");
                }
                String hashed = passwordEncoder.encode(defaultAdminPassword);
                admin.setPassword(hashed);
                admin.setMustChangePassword(true);
                userRepository.save(admin);
                log.warn("Admin password initialised from configured bootstrap value. Password change is required at first login.");
            }
        });
    }
}
