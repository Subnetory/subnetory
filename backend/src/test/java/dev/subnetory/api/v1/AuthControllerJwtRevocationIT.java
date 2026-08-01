package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.dto.TokenResponse;
import dev.subnetory.repository.AuthAuditLogRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AuthControllerJwtRevocationIT {

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired AuthAuditLogRepository authAuditLogRepository;

    @Test
    @DisplayName("JWT revocation lifecycle — token 200, logout 204, same token 401, another token 200")
    void logout_revokesOnlyCurrentToken() throws Exception {
        String firstToken = authenticate();
        String secondToken = authenticate();

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/contexts")
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /auth/logout — double logout avec le meme token reste idempotent")
    void logout_sameTokenTwice_returns204Twice() throws Exception {
        String token = authenticate();

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /auth/logout — token historique sans jti retourne 200 informatif")
    void logout_tokenWithoutJti_returnsInformative200() throws Exception {
        String legacyToken = legacyTokenWithoutJti();

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not_revocable")));
    }

    @Test
    @DisplayName("POST /auth/logout — token revoque genere un audit TOKEN_REVOKED")
    void logout_recordsTokenRevokedAuditEvent() throws Exception {
        String token = authenticate();

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token)
                        .header("User-Agent", "revocation-it"))
                .andExpect(status().isNoContent());

        var events = authAuditLogRepository.findByEventTypeOrderByCreatedAtDesc("TOKEN_REVOKED");

        org.junit.jupiter.api.Assertions.assertFalse(events.isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals("admin", events.get(0).getUsername());
        org.junit.jupiter.api.Assertions.assertTrue(events.get(0).getMessage().contains("jti="));
    }

    private String authenticate() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class).accessToken();
    }

    private String legacyTokenWithoutJti() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("subnetory")
                .issuedAt(now)
                .expiresAt(now.plus(60, ChronoUnit.MINUTES))
                .subject("admin")
                .claim("roles", List.of("ROLE_ADMIN", "ROLE_NETWORK", "ROLE_IP"))
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
    }
}
