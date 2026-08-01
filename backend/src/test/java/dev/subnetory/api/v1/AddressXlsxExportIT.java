package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.AddressRequest;
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
 * Tests d'intÃ©gration â€” Export Excel des adresses IP.
 * Sprint 2.9 : {@code GET /api/v1/addresses/export/xlsx}
 *
 * <p>VÃ©rifie :</p>
 * <ul>
 *   <li>401 sans token</li>
 *   <li>200 avec token, Content-Type xlsx</li>
 *   <li>Content-Disposition attachment</li>
 *   <li>Fichier .xlsx parseable par Apache POI</li>
 *   <li>Header de la feuille alignÃ© sur CSV_HEADER</li>
 *   <li>DonnÃ©es prÃ©sentes dans la feuille</li>
 *   <li>Filtrage par subnetId</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressXlsxExportIT {

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
    static Long subnetIdA;
    static Long subnetIdB;

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

        MvcResult siteResult = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("XlsxAddr-SiteA", "XLSA", contextId))))
                .andExpect(status().isCreated()).andReturn();
        long siteId = objectMapper.readTree(
                siteResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult subnetAResult = mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("172.20.1.0/24", "Xlsx addr subnet A",
                                        null, contextId, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnetIdA = objectMapper.readTree(
                subnetAResult.getResponse().getContentAsString()).get("id").asLong();

        MvcResult subnetBResult = mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("172.20.2.0/24", "Xlsx addr subnet B",
                                        null, contextId, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnetIdB = objectMapper.readTree(
                subnetBResult.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AddressRequest("172.20.1.10",
                                        "aa:bb:cc:00:00:01", "xlsx-host-a1",
                                        "Xlsx test A1", subnetIdA, false, "manual"))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AddressRequest("172.20.2.10",
                                        null, "xlsx-host-b1",
                                        null, subnetIdB, false, "manual"))))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // SÃ©curitÃ©
    // ------------------------------------------------------------------

    @Test @Order(1)
    @DisplayName("GET /api/v1/addresses/export/xlsx â€” 401 sans token")
    void exportXlsx_returns401WithoutToken() throws Exception {
        mvc.perform(get("/api/v1/addresses/export/xlsx"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Format et content-type
    // ------------------------------------------------------------------

    @Test @Order(2)
    @DisplayName("GET /api/v1/addresses/export/xlsx â€” 200 avec token, Content-Type xlsx")
    void exportXlsx_returns200WithCorrectContentType() throws Exception {
        mvc.perform(get("/api/v1/addresses/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX_MIME));
    }

    @Test @Order(3)
    @DisplayName("GET /api/v1/addresses/export/xlsx â€” Content-Disposition attachment .xlsx")
    void exportXlsx_hasContentDispositionAttachment() throws Exception {
        mvc.perform(get("/api/v1/addresses/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("addresses_")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xlsx")));
    }

    // ------------------------------------------------------------------
    // Contenu POI
    // ------------------------------------------------------------------

    @Test @Order(4)
    @DisplayName("GET /api/v1/addresses/export/xlsx â€” fichier .xlsx parseable par Apache POI")
    void exportXlsx_isValidXlsxFile() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();

        // VÃ©rifier que le fichier est un xlsx valide (parseable sans exception)
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
        }
    }

    @Test @Order(5)
    @DisplayName("GET /api/v1/addresses/export/xlsx â€” feuille nommÃ©e 'Adresses'")
    void exportXlsx_sheetNameIsAdresses() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("Adresses");
        }
    }

    @Test @Order(6)
    @DisplayName("GET /api/v1/addresses/export/xlsx â€” header ligne 0 correct")
    void exportXlsx_headerRowMatchesCsvHeader() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);

            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("address");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("subnet_id");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("subnet_network");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("mac");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("hostname");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("description");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("temporary");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("discovery_source");
        }
    }

    @Test @Order(7)
    @DisplayName("GET /api/v1/addresses/export/xlsx â€” donnÃ©es de l'adresse crÃ©Ã©e prÃ©sentes")
    void exportXlsx_containsCreatedAddress() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            // Au moins 2 lignes : header + 1 donnÃ©e minimum
            assertThat(sheet.getLastRowNum()).isGreaterThanOrEqualTo(1);

            // Chercher la ligne contenant l'adresse attendue
            boolean found = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null && "172.20.1.10".equals(row.getCell(0).getStringCellValue())) {
                    assertThat(row.getCell(4).getStringCellValue()).isEqualTo("xlsx-host-a1");
                    found = true;
                    break;
                }
            }
            assertThat(found).as("L'adresse 172.20.1.10 devrait Ãªtre prÃ©sente dans le xlsx").isTrue();
        }
    }

    @Test @Order(8)
    @DisplayName("GET /api/v1/addresses/export/xlsx?subnetId=A â€” filtre par subnet")
    void exportXlsx_filteredBySubnetId() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/addresses/export/xlsx")
                        .param("subnetId", String.valueOf(subnetIdA))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);

            boolean foundA = false, foundB = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String addr = row.getCell(0).getStringCellValue();
                if ("172.20.1.10".equals(addr)) foundA = true;
                if ("172.20.2.10".equals(addr)) foundB = true;
            }
            assertThat(foundA).as("172.20.1.10 du subnet A devrait Ãªtre prÃ©sente").isTrue();
            assertThat(foundB).as("172.20.2.10 du subnet B ne devrait pas Ãªtre prÃ©sente").isFalse();
        }
    }
}
