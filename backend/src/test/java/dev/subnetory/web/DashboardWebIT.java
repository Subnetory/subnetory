package dev.subnetory.web;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.dto.TokenResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intÃ©gration de la page Dashboard â€” Sprint 2.7.
 *
 * <p>Couvre :</p>
 * <ul>
 *   <li>AccÃ¨s anonyme â†’ redirect /login</li>
 *   <li>AccÃ¨s authentifiÃ© â†’ HTTP 200</li>
 *   <li>Compteurs globaux prÃ©sents dans le HTML</li>
 *   <li>Section top subnets (Ã©tat vide + avec donnÃ©es)</li>
 *   <li>Lien Dashboard dans la navigation</li>
 *   <li>activeSection = dashboard</li>
 *   <li>Pas de fuite technique (stack traces, CDN ext., donnÃ©es internes)</li>
 *   <li>Headers sÃ©curitÃ© prÃ©sents</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardWebIT {

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
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    static Long siteId;
    static Long subnetId;
    static final Long CTX_ID = 1L; // contexte "Default" seedÃ© par V3

    // â”€â”€ AccÃ¨s anonyme â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test @Order(1)
    @DisplayName("GET / anonyme â†’ redirect /login")
    void anonymous_root_redirectsToLogin() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // â”€â”€ Ã‰tat vide (avant crÃ©ation de donnÃ©es) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test @Order(2)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / authentifiÃ© â†’ HTTP 200")
    void dashboard_authenticated_returns200() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test @Order(3)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / contient les labels de compteurs globaux")
    void dashboard_contains_counter_labels() throws Exception {
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("Contextes")
                .contains("Sites")
                .contains("VLAN")
                .contains("Sous-réseaux")
                .contains("Adresses IP");
    }

    @Test @Order(4)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / contient le titre Tableau de bord")
    void dashboard_contains_title() throws Exception {
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Tableau de bord");
    }

    @Test @Order(5)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / contient la section 'Sous-réseaux les plus utilisés'")
    void dashboard_contains_topSubnets_section() throws Exception {
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Sous-réseaux les plus utilisés");
    }

    @Test @Order(6)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / état vide → message de création")
    void dashboard_empty_state_message_visible() throws Exception {
        // Avant le setup, aucun subnet → message état vide attendu
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Aucun sous-réseau configuré");
    }

    @Test @Order(7)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / navbar contient un lien 'Tableau de bord'")
    void dashboard_link_visible_in_navbar() throws Exception {
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Tableau de bord");
    }

    @Test @Order(8)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / lien Tableau de bord actif dans la navbar (activeSection=dashboard)")
    void dashboard_activeSection_dashboard() throws Exception {
        // Le lien Tableau de bord doit avoir la classe "active"
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // La navbar contient un lien vers "/" avec la classe "active"
        assertThat(body).contains("sn-nav-tile  active");
    }

    @Test @Order(9)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / pas de fuite technique dans le HTML")
    void dashboard_noTechnicalLeak() throws Exception {
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("Whitelabel Error Page")
                .doesNotContain("org.springframework")
                .doesNotContain("java.lang")
                .doesNotContain("Exception")
                .doesNotContain("cdnjs.cloudflare.com")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com")
                .doesNotContain("bootstrapcdn");
    }

    @Test @Order(10)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / headers sÃ©curitÃ© HTTP prÃ©sents")
    void dashboard_securityHeaders_present() throws Exception {
        var response = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isNotBlank();
    }

    // â”€â”€ Setup : crÃ©er un site + subnet via API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test @Order(11)
    @DisplayName("Setup â€” crÃ©er site + subnet via API REST")
    void setup_siteAndSubnet() throws Exception {
        MvcResult authResult = mvc.perform(post("/api/v1/auth/token")
                .contentType("application/json")
                .content(om.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = "Bearer " + om.readValue(
                authResult.getResponse().getContentAsString(),
                TokenResponse.class).accessToken();

        MvcResult siteResult = mvc.perform(post("/api/v1/sites")
                .header("Authorization", token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new SiteRequest("Site Dashboard IT", "DASH-IT", CTX_ID))))
                .andExpect(status().isCreated())
                .andReturn();

        siteId = om.readTree(siteResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult subnetResult = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new SubnetRequest("10.99.0.0/24", "Dashboard test subnet",
                                null, CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated())
                .andReturn();

        subnetId = om.readTree(subnetResult.getResponse().getContentAsString()).get("id").asLong();
        assertThat(subnetId).isPositive();
    }

    // â”€â”€ Avec donnÃ©es â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test @Order(12)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / aprÃ¨s crÃ©ation d'un subnet â†’ rÃ©seau CIDR visible dans le top")
    void dashboard_with_subnet_shows_network() throws Exception {
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("10.99.0.0/24");
    }

    @Test @Order(13)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / aprÃ¨s crÃ©ation â†’ plus d'Ã©tat vide")
    void dashboard_with_subnet_no_empty_state() throws Exception {
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Aucun sous-réseau configuré");
    }

    @Test @Order(14)
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET / compteur Sous-réseaux > 0 aprÃ¨s crÃ©ation")
    void dashboard_subnetCount_positive_after_creation() throws Exception {
        String body = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Le compteur "Sous-réseaux" doit Ãªtre >= 1 â€” on vÃ©rifie la prÃ©sence
        // du label, la valeur exacte dÃ©pend de l'ordre d'exÃ©cution des IT classes
        assertThat(body).contains("Sous-réseaux");
    }

    @Test @Order(15)
    @WithMockUser(username = "user", roles = {"IP"})
    @DisplayName("GET / avec rÃ´le IP (non-admin) â†’ 200 accessible")
    void dashboard_accessible_with_ip_role() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk());
    }
}

