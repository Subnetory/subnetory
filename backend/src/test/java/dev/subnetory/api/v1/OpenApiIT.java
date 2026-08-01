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
 *   <li>VÃ©rifier que la spec OpenAPI est accessible sans token (permitAll).</li>
 *   <li>VÃ©rifier que Swagger UI est accessible sans token.</li>
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
    MockMvc mvc;

    // ------------------------------------------------------------------
    // AccÃ¨s documentation â€” sans token
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /v3/api-docs â€” spec JSON accessible sans token")
    void apiDocs_isPublic() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    @DisplayName("GET /v3/api-docs â€” spec contient les tags attendus")
    void apiDocs_containsExpectedPaths() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/addresses']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/subnets']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/token']").exists())
                // Phase 7 (audit 31/07/2026) : non-regression pour la documentation
                // OpenAPI des endpoints de sauvegarde/restauration.
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/runs']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/trigger']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/restore']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/restores']").exists())
                // Audit du 01/08/2026 : import de sauvegarde externe et purge manuelle de l'historique.
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/import']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/purge']").exists())
                // Audit du 01/08/2026 : suppression fine d'une seule sauvegarde.
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/runs/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/backup/runs/{id}/linked-restores']").exists());
    }

    @Test
    @DisplayName("GET /v3/api-docs â€” schÃ©ma de sÃ©curitÃ© bearerAuth dÃ©clarÃ©")
    void apiDocs_hasBearerAuthSecurityScheme() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    @DisplayName("GET /swagger-ui.html â€” Swagger UI accessible sans token (redirection ou 200)")
    void swaggerUi_isPublic() throws Exception {
        // SpringDoc redirige /swagger-ui.html vers /swagger-ui/index.html
        // On accepte 200 ou 302 : les deux indiquent que la chaÃ®ne de sÃ©curitÃ© laisse passer.
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(302)
                )));
    }

    @Test
    @DisplayName("GET /swagger-ui/index.html â€” page Swagger UI accessible sans token")
    void swaggerUiIndex_isPublic() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
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
