package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.dto.TokenResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration SubnetController.
 *
 * <p>Pattern de référence Sprint 1 — PostgreSQL réel via Testcontainers.
 * Tous les tests s'exécutent sur un vrai PostgreSQL 17, Flyway applique
 * les migrations, aucun mock de couche DB.</p>
 *
 * <p>Couvre le cycle complet : auth JWT → CRUD subnet → IPs disponibles.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubnetControllerIT {

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

    @Autowired
    ObjectMapper objectMapper;

    // Token JWT récupéré une fois pour les tests Write
    static String adminToken;
    // ID du subnet créé, réutilisé entre tests
    static Long createdSubnetId;
    // ID du site de test
    static Long testSiteId;
    // ID du contexte seed "Default" créé par V3__seed_default_context.sql
    static final Long SEED_CONTEXT_ID = 1L;

    // -------------------------------------------------------
    // Setup : récupérer le token admin
    // -------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Auth — login admin retourne un JWT valide")
    void auth_loginAdmin_returnsJwt() throws Exception {
        TokenRequest body = new TokenRequest("admin", "admin");

        MvcResult result = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        TokenResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), TokenResponse.class);
        adminToken = response.accessToken();
    }

    // -------------------------------------------------------
    // Setup : créer un site de test
    // -------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("Site — créer un site de test pour les subnets")
    void site_create_forSubnetTests() throws Exception {
        SiteRequest body = new SiteRequest("Site Intégration Test", "SIT-TEST", SEED_CONTEXT_ID);

        MvcResult result = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SIT-TEST"))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        testSiteId = objectMapper.readTree(json).get("id").asLong();
    }

    // -------------------------------------------------------
    // GET list — non authentifié
    // -------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("GET /subnets — sans token → 401")
    void listSubnets_noAuth_returns401() throws Exception {
        mvc.perform(get("/api/v1/subnets"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------
    // POST — créer un subnet
    // -------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("POST /subnets — créer subnet valide → 201")
    void createSubnet_valid_returns201() throws Exception {
        SubnetRequest body = new SubnetRequest(
                "10.10.0.0/24",
                "Réseau de test intégration",
                "10.10.0.1",
                SEED_CONTEXT_ID,
                testSiteId,
                null, null
        );

        MvcResult result = mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.network").value("10.10.0.0/24"))
                .andExpect(jsonPath("$.gateway").value("10.10.0.1"))
                .andExpect(jsonPath("$.contextId").value(SEED_CONTEXT_ID))
                .andExpect(jsonPath("$.siteId").value(testSiteId))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        createdSubnetId = objectMapper.readTree(json).get("id").asLong();
    }

    @Test
    @Order(5)
    @DisplayName("POST /subnets — CIDR invalide → 400")
    void createSubnet_invalidCidr_returns400() throws Exception {
        SubnetRequest body = new SubnetRequest(
                "not-a-cidr", null, null, SEED_CONTEXT_ID, testSiteId, null, null);

        mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    @Order(6)
    @DisplayName("POST /subnets — doublon réseau+site → 409")
    void createSubnet_duplicate_returns409() throws Exception {
        SubnetRequest body = new SubnetRequest(
                "10.10.0.0/24", "Doublon", null, SEED_CONTEXT_ID, testSiteId, null, null);

        mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    // -------------------------------------------------------
    // GET by id
    // -------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("GET /subnets/{id} — subnet existant → 200")
    void getSubnet_exists_returns200() throws Exception {
        mvc.perform(get("/api/v1/subnets/" + createdSubnetId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdSubnetId))
                .andExpect(jsonPath("$.network").value("10.10.0.0/24"));
    }

    @Test
    @Order(8)
    @DisplayName("GET /subnets/{id} — ID inexistant → 404")
    void getSubnet_notFound_returns404() throws Exception {
        mvc.perform(get("/api/v1/subnets/99999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }

    // -------------------------------------------------------
    // IPs disponibles
    // -------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("GET /subnets/{id}/available-ips — exclut la gateway du subnet")
    void availableIps_emptySubnet_returnsManyIps() throws Exception {
        mvc.perform(get("/api/v1/subnets/" + createdSubnetId + "/available-ips")
                        .param("count", "5")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network").value("10.10.0.0/24"))
                .andExpect(jsonPath("$.found").value(5))
                .andExpect(jsonPath("$.availableIps", hasSize(5)))
                // La première IP dispo doit être 10.10.0.2 car 10.10.0.1 est la gateway
                .andExpect(jsonPath("$.availableIps[0]").value("10.10.0.2"))
                .andExpect(jsonPath("$.availableIps", not(hasItem("10.10.0.1"))));
    }

    // -------------------------------------------------------
    // PUT — mise à jour
    // -------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("PUT /subnets/{id} — mise à jour description → 200")
    void updateSubnet_validChange_returns200() throws Exception {
        SubnetRequest body = new SubnetRequest(
                "10.10.0.0/24",
                "Description mise à jour",
                "10.10.0.254",
                SEED_CONTEXT_ID,
                testSiteId,
                null, null
        );

        mvc.perform(put("/api/v1/subnets/" + createdSubnetId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Description mise à jour"))
                .andExpect(jsonPath("$.gateway").value("10.10.0.254"));
    }

    // -------------------------------------------------------
    // DELETE
    // -------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("DELETE /subnets/{id} — suppression → 204")
    void deleteSubnet_exists_returns204() throws Exception {
        mvc.perform(delete("/api/v1/subnets/" + createdSubnetId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(12)
    @DisplayName("DELETE /subnets/{id} — après suppression → 404")
    void deleteSubnet_alreadyDeleted_returns404() throws Exception {
        mvc.perform(delete("/api/v1/subnets/" + createdSubnetId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}

