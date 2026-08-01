package dev.subnetory.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.dto.TokenResponse;
import dev.subnetory.repository.AuthAuditLogRepository;
import dev.subnetory.repository.UserTokenInvalidationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AuthControllerLogoutAllIT {

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
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private AuthAuditLogRepository authAuditLogRepository;

    @Autowired
    private UserTokenInvalidationRepository userTokenInvalidationRepository;

    @BeforeEach
    void cleanState() {
        userTokenInvalidationRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /auth/logout-all â€” invalide tous les tokens API du sujet authentifie")
    void logoutAll_invalidatesAllTokensForCurrentSubjectOnly() throws Exception {
        String firstAdminToken = authenticate();
        String secondAdminToken = authenticate();
        String otherUserToken = tokenFor("other-user");

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + firstAdminToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + secondAdminToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout-all")
                        .header("Authorization", "Bearer " + firstAdminToken)
                        .header("User-Agent", "logout-all-it"))
                .andExpect(status().isNoContent());

        assertThat(userTokenInvalidationRepository.findNotBeforeByUsername("admin")).isPresent();

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + firstAdminToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + secondAdminToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk());

        waitForNextJwtSecond();
        String newAdminToken = authenticate();

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + newAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /auth/logout-all â€” le meme token retourne 401 apres invalidation")
    void logoutAll_sameTokenSecondCallReturns401() throws Exception {
        String token = authenticate();

        mvc.perform(post("/api/v1/auth/logout-all")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/logout-all")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /auth/logout-all â€” genere un audit TOKENS_INVALIDATED")
    void logoutAll_recordsTokensInvalidatedAuditEvent() throws Exception {
        String token = authenticate();

        mvc.perform(post("/api/v1/auth/logout-all")
                        .header("Authorization", "Bearer " + token)
                        .header("User-Agent", "logout-all-audit-it"))
                .andExpect(status().isNoContent());

        var events = authAuditLogRepository.findByEventTypeOrderByCreatedAtDesc("TOKENS_INVALIDATED");

        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getUsername()).isEqualTo("admin");
        assertThat(events.get(0).getTargetUsername()).isEqualTo("admin");
        assertThat(events.get(0).getMessage()).contains("reason=LOGOUT_ALL");
    }

    @Test
    @DisplayName("Interaction 2.26/2.27 - token revoque par jti ET anterieur au not_before : 401 propre")
    void logoutAll_rejectsTokenBothRevokedAndBeforeThreshold() throws Exception {
        String token = authenticate();

        // 1. Revocation unitaire (denylist jti, Sprint 2.26).
        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 2. Invalidation globale du sujet (not_before, Sprint 2.27),
        //    posterieure a l'emission du token.
        waitForNextJwtSecond();
        userTokenInvalidationRepository.upsertNotBefore(
                "admin",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                "it",
                "ADMIN_REVOKE");

        // 3. Le token cumule les deux motifs de rejet : le resource server
        //    doit repondre 401, sans erreur serveur.
        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String authenticate() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class).accessToken();
    }

    private String tokenFor(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("subnetory")
                .issuedAt(now)
                .expiresAt(now.plus(60, ChronoUnit.MINUTES))
                .id("manual-" + subject + "-" + now.toEpochMilli())
                .subject(subject)
                .claim("roles", List.of("ROLE_ADMIN", "ROLE_NETWORK", "ROLE_IP"))
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
    }

    private static void waitForNextJwtSecond() throws InterruptedException {
        Thread.sleep(1_200L);
    }
}
