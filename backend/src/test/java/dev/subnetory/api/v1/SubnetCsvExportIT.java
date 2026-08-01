package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SubnetRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Export CSV des sous-réseaux.
 * Sprint 2.8 : {@code GET /api/v1/subnets/export/csv}
 *
 * <p>Vérifie :</p>
 * <ul>
 *   <li>401 sans token</li>
 *   <li>200 avec token, Content-Type text/csv</li>
 *   <li>Header CSV complet</li>
 *   <li>Présence des données créées dans l'export</li>
 *   <li>Filtrage par siteId</li>
 *   <li>Export vide (filtre sans résultat) → uniquement le header</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubnetCsvExportIT {

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

    static String adminToken;
    static Long siteIdX;
    static Long siteIdY;

    // ------------------------------------------------------------------
    // Setup : login + données de test
    // ------------------------------------------------------------------

    @BeforeAll
    static void setup(@Autowired MockMvc mvc,
                      @Autowired ObjectMapper objectMapper) throws Exception {
        // Login
        MvcResult loginResult = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andReturn();
        TokenResponse tokenResponse = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), TokenResponse.class);
        adminToken = tokenResponse.accessToken();

        long contextId = 1L;

        // Site X
        MvcResult siteXResult = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("SubnetExport-SiteX", "SNEX", contextId))))
                .andExpect(status().isCreated())
                .andReturn();
        siteIdX = objectMapper.readTree(
                siteXResult.getResponse().getContentAsString()).get("id").asLong();

        // Site Y
        MvcResult siteYResult = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("SubnetExport-SiteY", "SNEY", contextId))))
                .andExpect(status().isCreated())
                .andReturn();
        siteIdY = objectMapper.readTree(
                siteYResult.getResponse().getContentAsString()).get("id").asLong();

        // Subnet sur site X
        mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("172.16.88.0/24", "Export subnet X",
                                        "172.16.88.1", contextId, siteIdX, null, null))))
                .andExpect(status().isCreated());

        // Subnet sur site Y
        mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("172.16.99.0/24", "Export subnet Y",
                                        null, contextId, siteIdY, null, null))))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Tests de sécurité
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("GET /api/v1/subnets/export/csv — 401 sans token")
    void exportCsv_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/subnets/export/csv"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Tests format et content-type
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("GET /api/v1/subnets/export/csv — 200 avec token, Content-Type text/csv")
    void exportCsv_returns200WithToken() throws Exception {
        mvc.perform(get("/api/v1/subnets/export/csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/v1/subnets/export/csv — Content-Disposition attachment")
    void exportCsv_hasContentDispositionAttachment() throws Exception {
        mvc.perform(get("/api/v1/subnets/export/csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("subnets_")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".csv")));
    }

    @Test
    @Order(4)
    @DisplayName("GET /api/v1/subnets/export/csv — header CSV complet")
    void exportCsv_headerIsComplete() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String firstLine = result.getResponse().getContentAsString()
                .lines().findFirst().orElse("");

        assertThat(firstLine).contains("network");
        assertThat(firstLine).contains("description");
        assertThat(firstLine).contains("gateway");
        assertThat(firstLine).contains("context_id");
        assertThat(firstLine).contains("site_id");
        assertThat(firstLine).contains("vlan_id");
        assertThat(firstLine).contains("parent_id");
    }

    // ------------------------------------------------------------------
    // Tests de contenu
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("GET /api/v1/subnets/export/csv — contient le subnet créé en setup")
    void exportCsv_containsCreatedSubnet() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("172.16.88.0/24");
        assertThat(body).contains("Export subnet X");
        assertThat(body).contains("172.16.88.1");
    }

    @Test
    @Order(6)
    @DisplayName("GET /api/v1/subnets/export/csv?siteId=X — filtre par siteId")
    void exportCsv_filteredBySiteId_containsOnlySiteXSubnets() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/csv")
                        .param("siteId", String.valueOf(siteIdX))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("172.16.88.0/24");      // subnet du site X
        assertThat(body).doesNotContain("172.16.99.0/24"); // subnet du site Y
    }

    @Test
    @Order(7)
    @DisplayName("GET /api/v1/subnets/export/csv — filtre sans résultat : uniquement le header")
    void exportCsv_noMatchingSubnets_returnsHeaderOnly() throws Exception {
        MvcResult emptySiteResult = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("SubnetExport-Empty", "SNEMPTY", 1L))))
                .andExpect(status().isCreated())
                .andReturn();
        long emptySiteId = objectMapper.readTree(
                emptySiteResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult result = mvc.perform(get("/api/v1/subnets/export/csv")
                        .param("siteId", String.valueOf(emptySiteId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString().trim();
        long lineCount = body.lines().count();
        assertThat(lineCount).isEqualTo(1);
        assertThat(body).contains("network");
    }

    @Test
    @Order(8)
    @DisplayName("GET /api/v1/subnets/export/csv?contextId= — filtre par contextId")
    void exportCsv_filteredByContextId_containsSubnets() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/csv")
                        .param("contextId", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Les deux subnets sont dans le contexte 1 (seed V3)
        assertThat(body).contains("172.16.88.0/24");
        assertThat(body).contains("172.16.99.0/24");
    }
}
