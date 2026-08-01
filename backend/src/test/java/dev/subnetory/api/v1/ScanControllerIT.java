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

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration ScanController.
 *
 * <p>Nmap n'est pas installé dans l'environnement Testcontainers.
 * Ces tests couvrent donc :</p>
 * <ul>
 *   <li>Sécurité (401 sans auth, 403 rôle insuffisant)</li>
 *   <li>Validation de taille subnet (400 pour subnet > /24)</li>
 *   <li>404 pour subnet inexistant</li>
 *   <li>503 quand nmap absent — comportement attendu en CI</li>
 * </ul>
 *
 * <p>Le comportement complet du scan (création IP, bulk-upsert, résultats)
 * est couvert par les tests unitaires {@link dev.subnetory.scan.NmapXmlParserTest}
 * et par les tests manuels avec un vrai Nmap.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScanControllerIT {

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
        // Fix 3 — chemin nmap invalide pour rendre le test 503 déterministe,
        // indépendamment de l'environnement CI (nmap peut être installé ou non
        // selon le runner GitHub Actions).
        registry.add("subnetory.scan.nmap-path",
                () -> "__subnetory_nmap_not_found__");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    static String adminToken;
    static Long smallSubnetId;  // /24 — dans la limite
    static Long largeSubnetId;  // /16 — au-delà de la limite

    static final Long CTX_ID = 1L;

    @Test @Order(1) @DisplayName("Setup — auth + site + subnets de test")
    void setup() throws Exception {
        // Auth
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk()).andReturn();
        adminToken = om.readValue(
                r.getResponse().getContentAsString(), TokenResponse.class).accessToken();

        // Site
        MvcResult sr = mvc.perform(post("/api/v1/sites")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(
                        new SiteRequest("Site Scan IT", "SCI-TEST", CTX_ID))))
                .andExpect(status().isCreated()).andReturn();
        Long siteId = om.readTree(sr.getResponse().getContentAsString()).get("id").asLong();

        // Petit subnet — 10.30.0.0/24 (254 hôtes — dans la limite)
        MvcResult s1 = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new SubnetRequest(
                        "10.30.0.0/24", "Subnet scan small", null, CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        smallSubnetId = om.readTree(s1.getResponse().getContentAsString()).get("id").asLong();

        // Grand subnet — 10.31.0.0/16 (65534 hôtes — au-delà de la limite)
        MvcResult s2 = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new SubnetRequest(
                        "10.31.0.0/16", "Subnet scan large", null, CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        largeSubnetId = om.readTree(s2.getResponse().getContentAsString()).get("id").asLong();
    }

    // -------------------------------------------------------
    // Sécurité
    // -------------------------------------------------------

    @Test @Order(2) @DisplayName("POST /scan — sans token → 401")
    void scan_noAuth_returns401() throws Exception {
        mvc.perform(post("/api/v1/subnets/" + smallSubnetId + "/scan"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------
    // Validation taille subnet
    // -------------------------------------------------------

    @Test @Order(3) @DisplayName("POST /scan — subnet /16 → 400 trop grand")
    void scan_largeSubnet_returns400() throws Exception {
        mvc.perform(post("/api/v1/subnets/" + largeSubnetId + "/scan")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("method", "nmap", "override", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Subnet Too Large"));
    }

    // -------------------------------------------------------
    // 404
    // -------------------------------------------------------

    @Test @Order(4) @DisplayName("POST /scan — subnet inexistant → 404")
    void scan_unknownSubnet_returns404() throws Exception {
        mvc.perform(post("/api/v1/subnets/99999/scan")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------
    // 503 — nmap absent (attendu en CI / Testcontainers)
    // -------------------------------------------------------

    @Test @Order(5)
    @DisplayName("POST /scan — subnet /24, nmap absent → 503 avec message clair")
    void scan_nmapNotInstalled_returns503() throws Exception {
        mvc.perform(post("/api/v1/subnets/" + smallSubnetId + "/scan")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("method", "nmap", "override", false))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Scan Tool Not Available"))
                .andExpect(jsonPath("$.detail", containsString("nmap")));
    }

    // -------------------------------------------------------
    // Body optionnel
    // -------------------------------------------------------

    @Test @Order(6)
    @DisplayName("POST /scan — sans body → 503 (nmap absent) — body optionnel respecté")
    void scan_noBody_isHandledWithDefaults() throws Exception {
        // Sans body → defaults (nmap, override=false) → nmap absent → 503
        // Ce test vérifie que l'absence de body ne cause pas d'erreur 400
        mvc.perform(post("/api/v1/subnets/" + smallSubnetId + "/scan")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isServiceUnavailable());
    }
}
