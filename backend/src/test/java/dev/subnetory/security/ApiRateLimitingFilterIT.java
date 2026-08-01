package dev.subnetory.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'integration du rate limiting generalise sur l'API (Sprint 2.36 / F7).
 *
 * <p>Le seuil par defaut du profil test (voir {@code application-test.yml})
 * est volontairement tres eleve pour ne pas interferer avec les autres
 * classes IT. Cette classe abaisse le seuil dans son propre contexte Spring
 * (isole par des proprietes de datasource Testcontainers dediees) afin de
 * declencher deliberement le blocage.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ApiRateLimitingFilterIT {

    private static final int THRESHOLD = 5;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("subnetory_test")
            .withUsername("subnetory")
            .withPassword("subnetory");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("subnetory.security.api-rate-limit.requests-per-window", () -> THRESHOLD);
        registry.add("subnetory.security.api-rate-limit.window-seconds", () -> 60);
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("Au-dela du seuil configure, l'API repond 429 avec Retry-After")
    void beyondThreshold_returns429WithRetryAfter() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"admin\"}";

        for (int i = 0; i < THRESHOLD; i++) {
            mvc.perform(post("/api/v1/auth/token")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/v1/auth/token")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("Les sondes /actuator/health ne sont jamais limitees")
    void healthProbe_isNeverRateLimited() throws Exception {
        for (int i = 0; i < 3 * THRESHOLD; i++) {
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk());
        }
    }
}
