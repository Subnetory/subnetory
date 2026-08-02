package dev.subnetory.web;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration GUI Thymeleaf — Sprint 2.1.
 *
 * <p>Couvre :</p>
 * <ul>
 *   <li>Accès anonyme → redirect login</li>
 *   <li>Pages réseau authentifiées → HTTP 200</li>
 *   <li>Assets publics accessibles sans auth</li>
 *   <li>Bouton scan et état non scannable</li>
 *   <li>Recherche adresses</li>
 *   <li>Détail adresse existante → 200, inexistante → 404 réel</li>
 *   <li>Sécurité : pas de fuite technique, pas de CDN, pas de "Nmap" GUI</li>
 *   <li>POST scan sans rôle NETWORK → 403</li>
 *   <li>POST scan avec NETWORK → fragment HTML (pas JSON brut)</li>
 *   <li>Headers sécurité HTTP présents sur les pages Web</li>
 *   <li>/actuator/health, liveness et readiness publics</li>
 *   <li>Readiness limitée à readinessState et db</li>
 *   <li>/actuator/info protégé</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetworkWebPagesIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("subnetory_test")
            .withUsername("subnetory")
            .withPassword("subnetory");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("subnetory.scan.nmap-path", () -> "__nmap_not_found_test__");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired Environment environment;

    static Long siteId;
    static Long subnetSmallId;
    static Long subnetLargeId;
    static Long addressId;
    static final Long CTX_ID = 1L;

    @Test @Order(1) @DisplayName("Setup — données de test via API")
    void setup() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                .contentType("application/json")
                .content(om.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk()).andReturn();
        String token = om.readValue(r.getResponse().getContentAsString(), TokenResponse.class).accessToken();
        String auth = "Bearer " + token;

        MvcResult sr = mvc.perform(post("/api/v1/sites").header("Authorization", auth)
                .contentType("application/json")
                .content(om.writeValueAsString(new SiteRequest("Site Web IT", "WEB-IT", CTX_ID))))
                .andExpect(status().isCreated()).andReturn();
        siteId = om.readTree(sr.getResponse().getContentAsString()).get("id").asLong();

        MvcResult s1 = mvc.perform(post("/api/v1/subnets").header("Authorization", auth)
                .contentType("application/json")
                .content(om.writeValueAsString(new SubnetRequest(
                        "10.60.0.0/24", "Scannable", null, CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnetSmallId = om.readTree(s1.getResponse().getContentAsString()).get("id").asLong();

        MvcResult s2 = mvc.perform(post("/api/v1/subnets").header("Authorization", auth)
                .contentType("application/json")
                .content(om.writeValueAsString(new SubnetRequest(
                        "10.61.0.0/16", "Non scannable", null, CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnetLargeId = om.readTree(s2.getResponse().getContentAsString()).get("id").asLong();

        MvcResult ar = mvc.perform(post("/api/v1/addresses").header("Authorization", auth)
                .contentType("application/json")
                .content(om.writeValueAsString(new AddressRequest(
                        "10.60.0.5", null, "srv-web-it", "Test", subnetSmallId, false, "manual"))))
                .andExpect(status().isCreated()).andReturn();
        addressId = om.readTree(ar.getResponse().getContentAsString()).get("id").asLong();
    }

    // --- Accès anonyme ---

    @Test @Order(2) @DisplayName("Anonyme /network/subnets → redirect /login")
    void anonymous_subnets_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/subnets"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test @Order(3) @DisplayName("Anonyme /network/addresses → redirect /login")
    void anonymous_addresses_redirects() throws Exception {
        mvc.perform(get("/network/addresses"))
                .andExpect(status().is3xxRedirection());
    }

    // --- Assets publics sans auth ---

    @Test @Order(4) @DisplayName("GET /assets/css/app.css → 200 sans auth")
    void assets_css_publicWithoutAuth() throws Exception {
        mvc.perform(get("/assets/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    @Test @Order(5) @DisplayName("GET /assets/js/app.js → 200 sans auth")
    void assets_js_publicWithoutAuth() throws Exception {
        mvc.perform(get("/assets/js/app.js"))
                .andExpect(status().isOk());
    }

    // --- Pages authentifiées ---

    @Test @Order(6) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /network/contexts → 200")
    void contexts_returns200() throws Exception {
        mvc.perform(get("/network/contexts")).andExpect(status().isOk());
    }

    @Test @Order(7) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /network/sites → 200")
    void sites_returns200() throws Exception {
        mvc.perform(get("/network/sites")).andExpect(status().isOk());
    }

    @Test @Order(8) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /network/vlans → 200")
    void vlans_returns200() throws Exception {
        mvc.perform(get("/network/vlans")).andExpect(status().isOk());
    }

    @Test @Order(9) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /network/subnets → 200")
    void subnets_returns200() throws Exception {
        mvc.perform(get("/network/subnets")).andExpect(status().isOk());
    }

    @Test @Order(10) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /network/addresses → 200")
    void addresses_returns200() throws Exception {
        mvc.perform(get("/network/addresses")).andExpect(status().isOk());
    }

    // --- Bouton scan ---

    @Test @Order(11) @WithMockUser(username = "admin", roles = {"ADMIN", "NETWORK"})
    @DisplayName("GET /network/subnets — subnet /24 contient l'action de configuration du scan")
    void subnets_smallSubnet_hasScanButton() throws Exception {
        MvcResult r = mvc.perform(get("/network/subnets"))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("Configurer");
    }

    @Test @Order(12) @WithMockUser(username = "admin", roles = {"ADMIN", "NETWORK"})
    @DisplayName("GET /network/subnets — subnet /16 affiche 'Non scannable'")
    void subnets_largeSubnet_showsNotScannable() throws Exception {
        MvcResult r = mvc.perform(get("/network/subnets"))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("Non scannable");
    }

    // --- Recherche et détail ---

    @Test @Order(13) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /network/addresses?q=web → 200")
    void addresses_search_returns200() throws Exception {
        mvc.perform(get("/network/addresses").param("q", "web"))
                .andExpect(status().isOk());
    }

    @Test @Order(14) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /network/addresses/{id} existant → 200 avec IP")
    void addressDetail_exists_returns200() throws Exception {
        mvc.perform(get("/network/addresses/" + addressId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("10.60.0.5")));
    }

    @Test @Order(15) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /network/addresses/99999 → HTTP 404 réel")
    void addressDetail_notFound_returnsHttp404() throws Exception {
        mvc.perform(get("/network/addresses/99999"))
                .andExpect(status().isNotFound());
    }

    // --- Sécurité — pas de fuite technique ---

    @Test @Order(16) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Pages réseau ne contiennent pas de fuite technique")
    void pages_noTechnicalLeak() throws Exception {
        String[] pages = {"/network/contexts", "/network/sites", "/network/subnets", "/network/addresses"};
        for (String page : pages) {
            String body = mvc.perform(get(page)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body)
                    .as("Page " + page + " ne doit pas exposer de stack technique")
                    .doesNotContain("Whitelabel Error Page")
                    .doesNotContain("org.springframework")
                    .doesNotContain("java.lang")
                    .doesNotContain("Exception")
                    .doesNotContain("cdnjs.cloudflare.com")
                    .doesNotContain("cdn.jsdelivr.net")
                    .doesNotContain("unpkg.com")
                    .doesNotContain("bootstrapcdn");
        }
    }

    // --- Sécurité — POST scan ---

    @Test @Order(17) @WithMockUser(username = "user", roles = {"IP"})
    @DisplayName("POST /network/subnets/{id}/scan sans rôle NETWORK → 403")
    void scan_withoutNetworkRole_returns403() throws Exception {
        mvc.perform(post("/network/subnets/" + subnetSmallId + "/scan")
                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test @Order(18) @WithMockUser(username = "admin", roles = {"ADMIN", "NETWORK"})
    @DisplayName("POST /network/subnets/{id}/scan → page de suivi")
    void scan_withNetworkRole_redirects() throws Exception {
        mvc.perform(post("/network/subnets/" + subnetSmallId + "/scan")
                .with(csrf()))
                .andExpect(status().isOk())
                // Thymeleaf th:text échappe l'apostrophe en entité HTML (&#39;).
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Suivi d&#39;exécution")));
    }

    @Test @Order(19) @WithMockUser(username = "admin", roles = {"ADMIN", "NETWORK"})
    @DisplayName("Page subnets après scan ne contient pas de détail technique")
    void scan_redirectTarget_hasNoTechnicalInfo() throws Exception {
        MvcResult r = mvc.perform(get("/network/subnets"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("nmap");
        assertThat(body).doesNotContain("ProcessBuilder");
    }

    @Test @Order(20) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Headers sécurité présents sur les pages Web")
    void webPages_haveSecurityHeaders() throws Exception {
        var response = mvc.perform(get("/network/subnets"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isNotBlank();
        assertThat(response.getHeader("Referrer-Policy")).isNotBlank();
    }

    // --- Actuator ---

    @Test @Order(21)
    @DisplayName("GET /actuator/health → 200 public (minimal)")
    void actuator_health_isPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test @Order(22)
    @DisplayName("GET /actuator/info anonyme → 401 ou 403 (protégé)")
    void actuator_info_isProtected() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().is4xxClientError());
    }

    @Test @Order(23) @WithMockUser(username = "admin", roles = {"ADMIN", "NETWORK"})
    @DisplayName("POST /network/subnets/{id}/scan sans CSRF → 403")
    void scan_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/subnets/" + subnetSmallId + "/scan"))
                .andExpect(status().isForbidden());
    }

    @Test @Order(24) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Pages réseau ne chargent pas HTMX")
    void pages_doNotLoadHtmx() throws Exception {
        String[] pages = {"/network/subnets", "/network/addresses"};
        for (String page : pages) {
            String body = mvc.perform(get(page)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body)
                    .as("Page " + page + " ne doit pas charger htmx.min.js")
                    .doesNotContain("htmx.min.js")
                    .doesNotContain("htmx.org");
        }
    }

    @Test @Order(25) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /actuator/info authentifié ADMIN → ne révèle pas de stack technique")
    void actuator_info_authenticated_noTechnicalLeak() throws Exception {
        MvcResult r = mvc.perform(get("/actuator/info")).andReturn();
        int status = r.getResponse().getStatus();
        if (status == 200) {
            String body = r.getResponse().getContentAsString();
            assertThat(body).doesNotContainIgnoringCase("SUBNETORY_");
            assertThat(body).doesNotContainIgnoringCase("password");
            assertThat(body).doesNotContainIgnoringCase("secret");
        }
    }

    @Test @Order(26) @DisplayName("POST /login sans CSRF → 403")
    void login_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/login")
                .param("username", "admin")
                .param("password", "admin"))
                .andExpect(status().isForbidden());
    }

    @Test @Order(27) @DisplayName("POST /login avec CSRF et bons identifiants → redirect")
    void login_withCsrfAndValidCredentials_redirects() throws Exception {
        mvc.perform(post("/login")
                .param("username", "admin")
                .param("password", "admin")
                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test @Order(28) @WithMockUser(username = "user", roles = {"IP"})
    @DisplayName("GET /network/subnets avec rôle IP → pas de bouton Scanner")
    void subnets_withIpRoleOnly_doesNotShowScanButton() throws Exception {
        MvcResult r = mvc.perform(get("/network/subnets"))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString())
                .doesNotContain("Scanner");
    }

    static Long nmapAddressId;

    @Test @Order(29) @DisplayName("Setup nmap — créer adresse avec discovery_source=nmap via API")
    void setup_nmapAddress() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                .contentType("application/json")
                .content(om.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk()).andReturn();
        String token = om.readValue(r.getResponse().getContentAsString(), TokenResponse.class).accessToken();

        var entries = java.util.List.of(
                new dev.subnetory.dto.BulkUpsertRequest.BulkUpsertEntry(
                        "10.60.0.99", subnetSmallId, null, "srv-scan-test",
                        "Test scan source", false, "nmap"));

        mvc.perform(post("/api/v1/addresses/bulk-upsert")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new dev.subnetory.dto.BulkUpsertRequest(entries, false))))
                .andExpect(status().isOk());

        MvcResult ar = mvc.perform(get("/api/v1/addresses/by-ip/10.60.0.99")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        nmapAddressId = om.readTree(ar.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test @Order(30) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Détail adresse discovery_source=nmap → affiche 'Scan', pas 'nmap'")
    void addressDetail_nmapSource_showsScanNotNmap() throws Exception {
        MvcResult r = mvc.perform(get("/network/addresses/" + nmapAddressId))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("Scan");
        assertThat(body).doesNotContainIgnoringCase("nmap");
    }
    @Test @Order(31)
    @DisplayName("GET /actuator/health/liveness anonyme → 200 UP")
    void actuator_liveness_isPublicAndUp() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(om.readTree(result.getResponse().getContentAsString())
                .path("status").asText()).isEqualTo("UP");
    }

    @Test @Order(32)
    @DisplayName("GET /actuator/health/readiness anonyme → 200 UP")
    void actuator_readiness_isPublicAndUp() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(om.readTree(result.getResponse().getContentAsString())
                .path("status").asText()).isEqualTo("UP");
    }

    @Test @Order(33) @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Readiness contient uniquement readinessState et db")
    void actuator_readiness_isLimitedToDatabase() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andReturn();

        var root = om.readTree(result.getResponse().getContentAsString());
        var components = root.path("components");
        assertThat(root.path("status").asText()).isEqualTo("UP");
        assertThat(components.has("readinessState")).isTrue();
        assertThat(components.has("db")).isTrue();
        assertThat(components.size()).isEqualTo(2);
        assertThat(root.toString()).doesNotContainIgnoringCase("ldap");
    }

    @Test @Order(34)
    @DisplayName("Arrêt gracieux configuré avec une phase de 30 secondes")
    void gracefulShutdownConfiguration_isLoaded() {
        assertThat(environment.getProperty("server.shutdown"))
                .isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("30s");
    }

}
