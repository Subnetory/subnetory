package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.domain.AuthAuditLog;
import dev.subnetory.dto.*;
import dev.subnetory.repository.AuthAuditLogRepository;
import dev.subnetory.service.AuthAuditService;
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
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration AddressController.
 *
 * <p>Couvre les types PostgreSQL natifs : INET pour les adresses,
 * MACADDR pour les adresses MAC, CIDR pour la vérification d'appartenance
 * au sous-réseau.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressControllerIT {

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
    @Autowired AuthAuditLogRepository authAuditLogRepository;

    static String adminToken;
    static Long testSiteId;
    static Long testSubnetId;
    static Long createdAddressId;

    static final Long SEED_CONTEXT_ID = 1L; // contexte "Default" créé par V3

    // -------------------------------------------------------
    // Setup
    // -------------------------------------------------------

    @Test @Order(1)
    @DisplayName("Setup — auth admin")
    void setup_auth() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk()).andReturn();
        adminToken = objectMapper.readValue(r.getResponse().getContentAsString(), TokenResponse.class).accessToken();
    }

    @Test @Order(2)
    @DisplayName("Setup — créer site + subnet de test")
    void setup_siteAndSubnet() throws Exception {
        // Site
        MvcResult sr = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("Site Address Test", "SAT-TEST", SEED_CONTEXT_ID))))
                .andExpect(status().isCreated()).andReturn();
        testSiteId = objectMapper.readTree(sr.getResponse().getContentAsString()).get("id").asLong();

        // Subnet
        MvcResult snr = mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("172.16.0.0/24", "Subnet address test",
                                        "172.16.0.1", SEED_CONTEXT_ID, testSiteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        testSubnetId = objectMapper.readTree(snr.getResponse().getContentAsString()).get("id").asLong();
    }

    // -------------------------------------------------------
    // POST — créer une adresse (INET + MACADDR)
    // -------------------------------------------------------

    @Test @Order(3)
    @DisplayName("POST /addresses — INET valide + MACADDR → 201")
    void createAddress_validInetAndMac_returns201() throws Exception {
        AddressRequest body = new AddressRequest(
                "172.16.0.10",       // INET
                "aa:bb:cc:dd:ee:ff", // MACADDR
                "srv-web-001",
                "Serveur web principal",
                testSubnetId,
                false,
                "manual"
        );

        MvcResult r = mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.address").value("172.16.0.10"))
                .andExpect(jsonPath("$.mac").value("aa:bb:cc:dd:ee:ff"))
                .andExpect(jsonPath("$.hostname").value("srv-web-001"))
                .andExpect(jsonPath("$.subnetId").value(testSubnetId))
                .andReturn();
        createdAddressId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();

        // Vérifie que la création est bien tracée dans le journal d'audit
        // (AuthAuditService.recordAddressCreated, backlog #27).
        java.util.List<AuthAuditLog> logs = authAuditLogRepository.findByEventType(
                AuthAuditService.ADDRESS_CREATED, org.springframework.data.domain.Pageable.unpaged()).getContent();
        assertThat(logs).anyMatch(log ->
                "admin".equals(log.getUsername())
                        && log.getMessage() != null
                        && log.getMessage().contains("id=" + createdAddressId)
                        && log.getMessage().contains("address=172.16.0.10"));
    }

    @Test @Order(4)
    @DisplayName("POST /addresses — doublon IP → 409")
    void createAddress_duplicateIp_returns409() throws Exception {
        AddressRequest body = new AddressRequest(
                "172.16.0.10", null, "srv-web-002", null, testSubnetId, false, "manual");
        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test @Order(5)
    @DisplayName("POST /addresses — IP hors subnet → 409 (vérification CIDR)")
    void createAddress_ipOutsideSubnet_returns409() throws Exception {
        // 192.168.1.1 n'appartient pas à 172.16.0.0/24
        AddressRequest body = new AddressRequest(
                "192.168.1.1", null, "hors-subnet", null, testSubnetId, false, "manual");
        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("not in subnet")));
    }

    @Test @Order(6)
    @DisplayName("POST /addresses — MACADDR invalide → 400")
    void createAddress_invalidMac_returns400() throws Exception {
        AddressRequest body = new AddressRequest(
                "172.16.0.20", "pas-une-mac", "srv-db-001", null, testSubnetId, false, "manual");
        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test @Order(7)
    @DisplayName("POST /addresses — IP invalide → 400")
    void createAddress_invalidInet_returns400() throws Exception {
        AddressRequest body = new AddressRequest(
                "999.999.999.999", null, "srv-invalid", null, testSubnetId, false, "manual");
        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------
    // GET
    // -------------------------------------------------------

    @Test @Order(8)
    @DisplayName("GET /addresses/{id} — adresse existante → 200 avec INET et MACADDR")
    void getAddress_exists_returnsInetAndMac() throws Exception {
        mvc.perform(get("/api/v1/addresses/" + createdAddressId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("172.16.0.10"))
                .andExpect(jsonPath("$.mac").value("aa:bb:cc:dd:ee:ff"));
    }

    @Test @Order(9)
    @DisplayName("GET /addresses/by-ip/{ip} — recherche par IP (host() PostgreSQL)")
    void getAddressByIp_exists_returns200() throws Exception {
        mvc.perform(get("/api/v1/addresses/by-ip/172.16.0.10")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("srv-web-001"));
    }

    @Test @Order(10)
    @DisplayName("GET /addresses/by-ip/{ip} — IP inexistante → 404")
    void getAddressByIp_notFound_returns404() throws Exception {
        mvc.perform(get("/api/v1/addresses/by-ip/1.2.3.4")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test @Order(11)
    @DisplayName("GET /addresses?subnetId= — liste filtrée par subnet")
    void listAddresses_filteredBySubnet_returnsOne() throws Exception {
        mvc.perform(get("/api/v1/addresses")
                        .param("subnetId", testSubnetId.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].address").value("172.16.0.10"));
    }

    // -------------------------------------------------------
    // IPs disponibles (IpAllocService via SubnetController)
    // -------------------------------------------------------

    @Test @Order(12)
    @DisplayName("GET /subnets/{id}/available-ips — gateway et IP assignée exclues")
    void availableIps_withOneAssigned_skipsAssigned() throws Exception {
        mvc.perform(get("/api/v1/subnets/" + testSubnetId + "/available-ips")
                        .param("count", "3")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(3))
                .andExpect(jsonPath("$.availableIps[0]").value("172.16.0.2"))
                // .1 est la gateway et .10 est assignée — elles ne doivent pas apparaître
                .andExpect(jsonPath("$.availableIps", not(hasItem("172.16.0.1"))))
                .andExpect(jsonPath("$.availableIps", not(hasItem("172.16.0.10"))));
    }

    // -------------------------------------------------------
    // DELETE
    // -------------------------------------------------------

    @Test @Order(13)
    @DisplayName("DELETE /addresses/{id} → 204")
    void deleteAddress_returns204() throws Exception {
        mvc.perform(delete("/api/v1/addresses/" + createdAddressId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Vérifie que la suppression est bien tracée dans le journal d'audit
        // (AuthAuditService.recordAddressDeleted, backlog #27).
        java.util.List<AuthAuditLog> logs = authAuditLogRepository.findByEventType(
                AuthAuditService.ADDRESS_DELETED, org.springframework.data.domain.Pageable.unpaged()).getContent();
        assertThat(logs).anyMatch(log ->
                "admin".equals(log.getUsername())
                        && log.getMessage() != null
                        && log.getMessage().contains("id=" + createdAddressId)
                        && log.getMessage().contains("address=172.16.0.10"));
    }
}
