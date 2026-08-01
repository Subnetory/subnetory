package dev.subnetory.web;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Endpoints Web session export adresses (Sprint 2.9).
 *
 * <p>Vérifie que les endpoints {@code /network/addresses/export/csv} et
 * {@code /network/addresses/export/xlsx} sont :</p>
 * <ul>
 *   <li>Accessibles via session Web (@WithMockUser), sans token JWT</li>
 *   <li>Protégés contre les accès anonymes (redirect /login)</li>
 *   <li>Cohérents en format avec leurs équivalents API REST</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AddressWebExportIT {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

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
    // Sécurité — accès anonyme
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /network/addresses/export/csv — anonyme → redirect /login")
    void exportCsv_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/addresses/export/csv"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("GET /network/addresses/export/xlsx — anonyme → redirect /login")
    void exportXlsx_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/addresses/export/xlsx"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ------------------------------------------------------------------
    // Export CSV via session
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "IP")
    @DisplayName("GET /network/addresses/export/csv — 200 avec session, Content-Type text/csv")
    void exportCsv_withSession_returns200() throws Exception {
        mvc.perform(get("/network/addresses/export/csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    @WithMockUser(roles = "IP")
    @DisplayName("GET /network/addresses/export/csv — Content-Disposition attachment")
    void exportCsv_withSession_hasAttachmentHeader() throws Exception {
        mvc.perform(get("/network/addresses/export/csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("addresses_")));
    }

    @Test
    @WithMockUser(roles = "IP")
    @DisplayName("GET /network/addresses/export/csv — header CSV présent")
    void exportCsv_withSession_hasHeader() throws Exception {
        MvcResult result = mvc.perform(get("/network/addresses/export/csv"))
                .andExpect(status().isOk())
                .andReturn();

        String firstLine = result.getResponse().getContentAsString()
                .lines().findFirst().orElse("");
        assertThat(firstLine).contains("address");
        assertThat(firstLine).contains("subnet_id");
        assertThat(firstLine).contains("hostname");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /network/addresses/export/csv — accessible avec rôle ADMIN")
    void exportCsv_withAdminRole_returns200() throws Exception {
        mvc.perform(get("/network/addresses/export/csv"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Export XLSX via session
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "IP")
    @DisplayName("GET /network/addresses/export/xlsx — 200 avec session, Content-Type xlsx")
    void exportXlsx_withSession_returns200() throws Exception {
        mvc.perform(get("/network/addresses/export/xlsx"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX_MIME));
    }

    @Test
    @WithMockUser(roles = "IP")
    @DisplayName("GET /network/addresses/export/xlsx — Content-Disposition attachment .xlsx")
    void exportXlsx_withSession_hasAttachmentHeader() throws Exception {
        mvc.perform(get("/network/addresses/export/xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xlsx")));
    }

    @Test
    @WithMockUser(roles = "IP")
    @DisplayName("GET /network/addresses/export/xlsx — fichier xlsx parseable par Apache POI")
    void exportXlsx_withSession_isValidXlsx() throws Exception {
        MvcResult result = mvc.perform(get("/network/addresses/export/xlsx"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("Adresses");
        }
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    @DisplayName("GET /network/addresses/export/xlsx — accessible avec rôle NETWORK")
    void exportXlsx_withNetworkRole_returns200() throws Exception {
        mvc.perform(get("/network/addresses/export/xlsx"))
                .andExpect(status().isOk());
    }
}
