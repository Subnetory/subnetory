package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.AddressRequest;
import dev.subnetory.dto.NetworkContextRequest;
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
 * Tests d'intÃ©gration â€” Export CSV des adresses IP.
 * Sprint 2.8 : {@code GET /api/v1/addresses/export/csv}
 *
 * <p>VÃ©rifie :</p>
 * <ul>
 *   <li>401 sans token</li>
 *   <li>200 avec token, Content-Type text/csv</li>
 *   <li>Header CSV alignÃ© sur CSV_IMPORT_FORMAT.md</li>
 *   <li>PrÃ©sence des donnÃ©es crÃ©Ã©es dans l'export</li>
 *   <li>Filtrage par subnetId</li>
 *   <li>Export vide (hors scope du filtre) â†’ uniquement le header</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressCsvExportIT {

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
    static Long subnetIdA;
    static Long subnetIdB;

    // ------------------------------------------------------------------
    // Setup : login + donnÃ©es de test
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

        // Contexte (utilise le contexte seed V3 id=1)
        long contextId = 1L;

        // Site A
        MvcResult siteResult = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("Export-Site-A", "EXPA", contextId))))
                .andExpect(status().isCreated())
                .andReturn();
        long siteId = objectMapper.readTree(
                siteResult.getResponse().getContentAsString()).get("id").asLong();

        // Subnet A â€” 192.168.99.0/24
        MvcResult subnetAResult = mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("192.168.99.0/24", "Export subnet A",
                                        null, contextId, siteId, null, null))))
                .andExpect(status().isCreated())
                .andReturn();
        subnetIdA = objectMapper.readTree(
                subnetAResult.getResponse().getContentAsString()).get("id").asLong();

        // Subnet B â€” 10.99.0.0/24
        MvcResult subnetBResult = mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("10.99.0.0/24", "Export subnet B",
                                        null, contextId, siteId, null, null))))
                .andExpect(status().isCreated())
                .andReturn();
        subnetIdB = objectMapper.readTree(
                subnetBResult.getResponse().getContentAsString()).get("id").asLong();

        // Adresse dans subnet A
        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AddressRequest("192.168.99.10",
                                        "aa:bb:cc:dd:ee:ff", "export-host-01",
                                        "Export test host", subnetIdA, false, "manual"))))
                .andExpect(status().isCreated());

        // Adresse dans subnet B
        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AddressRequest("10.99.0.20",
                                        null, "export-host-02",
                                        null, subnetIdB, false, "manual"))))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Tests de sÃ©curitÃ©
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("GET /api/v1/addresses/export/csv â€” 401 sans token")
    void exportCsv_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/addresses/export/csv"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Tests format et content-type
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("GET /api/v1/addresses/export/csv â€” 200 avec token, Content-Type text/csv")
    void exportCsv_returns200WithToken() throws Exception {
        mvc.perform(get("/api/v1/addresses/export/csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/v1/addresses/export/csv â€” Content-Disposition attachment")
    void exportCsv_hasContentDispositionAttachment() throws Exception {
        mvc.perform(get("/api/v1/addresses/export/csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("addresses_")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".csv")));
    }

    @Test
    @Order(4)
    @DisplayName("GET /api/v1/addresses/export/csv â€” header CSV alignÃ© sur CSV_IMPORT_FORMAT.md")
    void exportCsv_headerMatchesImportFormat() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String firstLine = body.lines().findFirst().orElse("");

        // VÃ©rification stricte du header â€” doit correspondre exactement Ã  CSV_IMPORT_FORMAT.md
        assertThat(firstLine).isEqualTo(
                "\"address\",\"subnet_id\",\"subnet_network\",\"mac\"," +
                "\"hostname\",\"description\",\"temporary\",\"discovery_source\""
        );
    }

    // ------------------------------------------------------------------
    // Tests de contenu
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("GET /api/v1/addresses/export/csv â€” contient l'adresse crÃ©Ã©e en setup")
    void exportCsv_containsCreatedAddress() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("192.168.99.10");
        assertThat(body).contains("export-host-01");
        assertThat(body).contains("aa:bb:cc:dd:ee:ff");
    }

    @Test
    @Order(6)
    @DisplayName("GET /api/v1/addresses/export/csv?subnetId=A â€” filtre par subnet")
    void exportCsv_filteredBySubnetId_containsOnlySubnetAAddresses() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/csv")
                        .param("subnetId", String.valueOf(subnetIdA))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("192.168.99.10");       // adresse du subnet A
        assertThat(body).doesNotContain("10.99.0.20");    // adresse du subnet B
    }

    @Test
    @Order(7)
    @DisplayName("GET /api/v1/addresses/export/csv â€” filtre sans rÃ©sultat : uniquement le header")
    void exportCsv_noMatchingAddresses_returnsHeaderOnly() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/csv")
                        .param("hostname", "inexistant-zzz-9999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString().trim();
        long lineCount = body.lines().count();
        // Exactement 1 ligne : le header uniquement
        assertThat(lineCount).isEqualTo(1);
        assertThat(body).contains("address");
    }

    @Test
    @Order(8)
    @DisplayName("GET /api/v1/addresses/export/csv â€” round-trip : subnet_id prÃ©sent dans l'export")
    void exportCsv_containsSubnetIdForRoundTrip() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/csv")
                        .param("subnetId", String.valueOf(subnetIdA))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // subnet_id prÃ©sent â†’ le fichier exportÃ© est rÃ©importable sans modification
        assertThat(body).contains(String.valueOf(subnetIdA));
        // subnet_network prÃ©sent â†’ alternative de rÃ©solution pour l'import
        assertThat(body).contains("192.168.99.0/24");
    }
}
