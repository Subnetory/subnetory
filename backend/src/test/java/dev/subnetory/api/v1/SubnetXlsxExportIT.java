package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.dto.TokenResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Export Excel des sous-réseaux.
 * Sprint 2.9 : {@code GET /api/v1/subnets/export/xlsx}
 *
 * <p>Vérifie :</p>
 * <ul>
 *   <li>401 sans token</li>
 *   <li>200 avec token, Content-Type xlsx</li>
 *   <li>Content-Disposition attachment</li>
 *   <li>Fichier .xlsx parseable par Apache POI</li>
 *   <li>Feuille nommée correctement</li>
 *   <li>Header aligné sur CSV_HEADER subnets</li>
 *   <li>Données présentes</li>
 *   <li>Filtrage par siteId</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubnetXlsxExportIT {

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    static String adminToken;
    static Long siteIdP;
    static Long siteIdQ;

    @BeforeAll
    static void setup(@Autowired MockMvc mvc,
                      @Autowired ObjectMapper objectMapper) throws Exception {
        MvcResult loginResult = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(),
                TokenResponse.class).accessToken();

        long contextId = 1L;

        MvcResult sitePResult = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("XlsxSubnet-SiteP", "XLSP", contextId))))
                .andExpect(status().isCreated()).andReturn();
        siteIdP = objectMapper.readTree(
                sitePResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult siteQResult = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("XlsxSubnet-SiteQ", "XLSQ", contextId))))
                .andExpect(status().isCreated()).andReturn();
        siteIdQ = objectMapper.readTree(
                siteQResult.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("10.200.1.0/24", "Xlsx subnet P",
                                        "10.200.1.1", contextId, siteIdP, null, null))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("10.200.2.0/24", "Xlsx subnet Q",
                                        null, contextId, siteIdQ, null, null))))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Sécurité
    // ------------------------------------------------------------------

    @Test @Order(1)
    @DisplayName("GET /api/v1/subnets/export/xlsx — 401 sans token")
    void exportXlsx_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/subnets/export/xlsx"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Format et content-type
    // ------------------------------------------------------------------

    @Test @Order(2)
    @DisplayName("GET /api/v1/subnets/export/xlsx — 200 avec token, Content-Type xlsx")
    void exportXlsx_returns200WithCorrectContentType() throws Exception {
        mvc.perform(get("/api/v1/subnets/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX_MIME));
    }

    @Test @Order(3)
    @DisplayName("GET /api/v1/subnets/export/xlsx — Content-Disposition attachment .xlsx")
    void exportXlsx_hasContentDispositionAttachment() throws Exception {
        mvc.perform(get("/api/v1/subnets/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("subnets_")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xlsx")));
    }

    // ------------------------------------------------------------------
    // Contenu POI
    // ------------------------------------------------------------------

    @Test @Order(4)
    @DisplayName("GET /api/v1/subnets/export/xlsx — fichier .xlsx parseable par Apache POI")
    void exportXlsx_isValidXlsxFile() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
        }
    }

    @Test @Order(5)
    @DisplayName("GET /api/v1/subnets/export/xlsx — feuille nommée 'Sous-réseaux'")
    void exportXlsx_sheetNameIsSousReseaux() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("Sous-réseaux");
        }
    }

    @Test @Order(6)
    @DisplayName("GET /api/v1/subnets/export/xlsx — header ligne 0 correct")
    void exportXlsx_headerRowIsComplete() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Row header = wb.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("network");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("description");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("gateway");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("context_id");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("context_name");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("site_id");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("site_name");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("vlan_id");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("parent_network");
        }
    }

    @Test @Order(7)
    @DisplayName("GET /api/v1/subnets/export/xlsx — subnet créé en setup présent")
    void exportXlsx_containsCreatedSubnet() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isGreaterThanOrEqualTo(1);

            boolean found = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null && "10.200.1.0/24".equals(row.getCell(0).getStringCellValue())) {
                    assertThat(row.getCell(2).getStringCellValue()).isEqualTo("10.200.1.1");
                    found = true;
                    break;
                }
            }
            assertThat(found).as("10.200.1.0/24 devrait être présent dans le xlsx").isTrue();
        }
    }

    @Test @Order(8)
    @DisplayName("GET /api/v1/subnets/export/xlsx?siteId=P — filtre par siteId")
    void exportXlsx_filteredBySiteId() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/subnets/export/xlsx")
                        .param("siteId", String.valueOf(siteIdP))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);

            boolean foundP = false, foundQ = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String net = row.getCell(0).getStringCellValue();
                if ("10.200.1.0/24".equals(net)) foundP = true;
                if ("10.200.2.0/24".equals(net)) foundQ = true;
            }
            assertThat(foundP).as("10.200.1.0/24 du site P devrait être présent").isTrue();
            assertThat(foundQ).as("10.200.2.0/24 du site Q ne devrait pas être présent").isFalse();
        }
    }
}
