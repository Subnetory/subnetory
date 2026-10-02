package dev.subnetory.api.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intÃ©gration OpenAPI â€” Sprint 2.8.
 *
 * <p>Objectifs :</p>
 * <ol>
 *   <li>Verify the OpenAPI spec requires an admin credential.</li>
 *   <li>Verify Swagger UI requires an admin credential.</li>
 *   <li>VÃ©rifier que les endpoints mÃ©tier restent protÃ©gÃ©s aprÃ¨s l'ajout d'OpenAPI.</li>
 *   <li>VÃ©rifier que l'authentification JWT fonctionne toujours (non-rÃ©gression Sprint 2.1).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class OpenApiIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres:17-alpine"))
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
    MockMvc mvc;

    // ------------------------------------------------------------------
    // OpenAPI documentation: ADMIN only (JWT Bearer or web session)
    // ------------------------------------------------------------------

    private String adminToken() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/token")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.accessToken");
    }

    @Test
    @DisplayName("GET /v3/api-docs - 401 without token")
    void apiDocs_withoutToken_returns401() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/api/v1/"))));
    }

    @Test
    @DisplayName("GET /v3/api-docs.yaml and swagger-config - 401 without token")
    void apiDocsVariants_withoutToken_return401() throws Exception {
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /swagger-ui.html and index.html - 401 without credentials")
    void swaggerUi_withoutToken_returns401() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /swagger-ui.html - browser without session is redirected to /login")
    void swaggerUi_browserWithoutSession_redirectsToLogin() throws Exception {
        mvc.perform(get("/swagger-ui.html").accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("GET /v3/api-docs - invalid token returns 401")
    void apiDocs_invalidToken_returns401() throws Exception {
        mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /v3/api-docs - 403 for an authenticated non-admin token")
    void apiDocs_nonAdminToken_returns403() throws Exception {
        mvc.perform(get("/v3/api-docs")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v3/api-docs - 403 for an authenticated non-admin web session")
    void apiDocs_nonAdminSession_returns403() throws Exception {
        mvc.perform(get("/v3/api-docs")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors
                                .user("docs-session-user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v3/api-docs - spec JSON accessible with an admin token")
    void apiDocs_adminToken_isOk() throws Exception {
        mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    @DisplayName("GET /v3/api-docs.yaml - accessible with an admin token")
    void apiDocsYaml_adminToken_isOk() throws Exception {
        mvc.perform(get("/v3/api-docs.yaml").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /v3/api-docs - accessible with an admin web session")
    void apiDocs_adminSession_isOk() throws Exception {
        mvc.perform(get("/v3/api-docs")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors
                                .user("docs-session-admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /v3/api-docs - spec contains the expected paths")
    void apiDocs_containsExpectedPaths() throws Exception {
        mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/addresses']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/subnets']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/token']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/runs']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/trigger']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/restore']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/restores']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/import']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/purge']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/runs/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/runs/{id}/linked-restores']").exists());
    }

    @Test
    @DisplayName("GET /v3/api-docs - bearerAuth security scheme declared")
    void apiDocs_hasBearerAuthSecurityScheme() throws Exception {
        mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    @DisplayName("GET /swagger-ui/index.html - accessible with an admin web session")
    void swaggerUiIndex_adminSession_isOk() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors
                                .user("docs-session-admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Non-rÃ©gression sÃ©curitÃ© â€” endpoints mÃ©tier toujours protÃ©gÃ©s
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/v1/addresses â€” 401 sans token (non-rÃ©gression)")
    void addresses_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/addresses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/subnets â€” 401 sans token (non-rÃ©gression)")
    void subnets_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/subnets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/vlans â€” 401 sans token (non-rÃ©gression)")
    void vlans_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/vlans"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/sites â€” 401 sans token (non-rÃ©gression)")
    void sites_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/sites"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/contexts â€” 401 sans token (non-rÃ©gression)")
    void contexts_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/contexts"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Non-rÃ©gression authentification JWT â€” POST /api/v1/auth/token
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/v1/auth/token â€” login admin/admin retourne un token (non-rÃ©gression JWT)")
    void authToken_adminLogin_returnsToken() throws Exception {
        mvc.perform(post("/api/v1/auth/token")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresInSeconds").isNumber());
    }

    @Test
    @DisplayName("POST /api/v1/auth/token â€” mauvais mot de passe retourne 401 (non-rÃ©gression JWT)")
    void authToken_wrongPassword_returns401() throws Exception {
        mvc.perform(post("/api/v1/auth/token")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"mauvais\"}"))
                .andExpect(status().isUnauthorized());
    }
}
