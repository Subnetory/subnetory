package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.dto.TokenResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for XLSX address import.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressXlsxImportIT {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String[] STANDARD_HEADER = {
            "address", "subnet_id", "subnet_network",
            "mac", "hostname", "description", "temporary", "discovery_source"
    };

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

    static String token;
    static Long siteId;
    static Long subnetId;
    static Long subnet2Id;
    static Long uniqueSubnetId;

    static final Long CTX_ID = 1L;

    @Test
    @Order(1)
    @DisplayName("Setup - auth + sites + subnets")
    void setup() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                .contentType("application/json")
                .content(om.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk())
                .andReturn();

        token = om.readValue(r.getResponse().getContentAsString(), TokenResponse.class).accessToken();

        MvcResult sr = mvc.perform(post("/api/v1/sites")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(new SiteRequest("Site XLSX Import", "XLSX-TEST", CTX_ID))))
                .andExpect(status().isCreated())
                .andReturn();

        siteId = om.readTree(sr.getResponse().getContentAsString()).get("id").asLong();

        MvcResult s1 = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new SubnetRequest("10.60.0.0/24", "Subnet XLSX test", null, CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated())
                .andReturn();

        subnetId = om.readTree(s1.getResponse().getContentAsString()).get("id").asLong();

        MvcResult sr2 = mvc.perform(post("/api/v1/sites")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(new SiteRequest("Site XLSX Import 2", "XLSX-TEST2", CTX_ID))))
                .andExpect(status().isCreated())
                .andReturn();

        Long siteId2 = om.readTree(sr2.getResponse().getContentAsString()).get("id").asLong();

        MvcResult s2 = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new SubnetRequest("10.60.0.0/24", "Subnet XLSX ambiguity", null, CTX_ID, siteId2, null, null))))
                .andExpect(status().isCreated())
                .andReturn();

        subnet2Id = om.readTree(s2.getResponse().getContentAsString()).get("id").asLong();

        MvcResult s3 = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new SubnetRequest("10.61.0.0/24", "Subnet XLSX unique", null, CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated())
                .andReturn();

        uniqueSubnetId = om.readTree(s3.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    @Order(2)
    @DisplayName("Anonymous XLSX import returns 401")
    void importXlsx_anonymous_returns401() throws Exception {
        MockMultipartFile file = xlsxFile("anonymous.xlsx",
                new Object[]{"10.60.0.10", 1L, null, null, "anon", null, false, null});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    @DisplayName("Valid XLSX with subnet_id creates addresses")
    void importXlsx_validWithSubnetId_createsAddresses() throws Exception {
        MockMultipartFile file = xlsxFile("valid_subnet_id.xlsx",
                new Object[]{"10.60.0.10", subnetId, null, null, "xlsx-import-01", "XLSX import", false, null},
                new Object[]{"10.60.0.11", subnetId, null, null, "xlsx-import-02", "XLSX import", true, null});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.errors").value(0));
    }

    @Test
    @Order(4)
    @DisplayName("Valid XLSX with subnet_network unique creates address")
    void importXlsx_validWithSubnetNetwork_createsAddress() throws Exception {
        MockMultipartFile file = xlsxFile("valid_subnet_network.xlsx",
                new Object[]{"10.61.0.5", null, "10.61.0.0/24", null, "xlsx-network-01", null, false, null});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors").value(0));
    }

    @Test
    @Order(5)
    @DisplayName("Empty XLSX file returns 400")
    void importXlsx_emptyFile_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.xlsx", XLSX_CONTENT_TYPE, new byte[0]);

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(6)
    @DisplayName("Missing address header returns 400")
    void importXlsx_missingAddressHeader_returns400() throws Exception {
        MockMultipartFile file = xlsxFileWithHeader(
                "missing_address.xlsx",
                new String[]{"subnet_id", "hostname"},
                new Object[]{"1", "host-without-address"});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("address")));
    }

    @Test
    @Order(7)
    @DisplayName("Invalid IP produces row error")
    void importXlsx_invalidIp_reportsRowError() throws Exception {
        MockMultipartFile file = xlsxFile("invalid_ip.xlsx",
                new Object[]{"999.999.999.999", subnetId, null, null, "bad-ip", null, false, null});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.errors").value(1));
    }

    @Test
    @Order(8)
    @DisplayName("Unknown subnet_id produces row error")
    void importXlsx_unknownSubnetId_reportsRowError() throws Exception {
        MockMultipartFile file = xlsxFile("unknown_subnet.xlsx",
                new Object[]{"10.60.0.200", 999999L, null, null, "ghost", null, false, null});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").value(1));
    }

    @Test
    @Order(9)
    @DisplayName("Ambiguous subnet_network produces row error")
    void importXlsx_ambiguousSubnetNetwork_reportsRowError() throws Exception {
        MockMultipartFile file = xlsxFile("ambiguous_network.xlsx",
                new Object[]{"10.60.0.210", null, "10.60.0.0/24", null, "ambiguous", null, false, null});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.errors").value(1))
                .andExpect(jsonPath("$.errorDetails[0].reason", containsString("matches 2 subnets")));
    }

    @Test
    @Order(10)
    @DisplayName("override=true updates existing hostname")
    void importXlsx_override_updatesExistingHostname() throws Exception {
        MockMultipartFile file = xlsxFile("override.xlsx",
                new Object[]{"10.60.0.10", subnetId, null, null, "xlsx-import-01-updated", null, false, null});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .param("override", "true")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedLastSeen").value(greaterThanOrEqualTo(0)));

        mvc.perform(get("/api/v1/addresses/by-ip/10.60.0.10")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("xlsx-import-01-updated"));
    }

    @Test
    @Order(11)
    @DisplayName("Mixed XLSX import returns coherent report")
    void importXlsx_mixed_returnsCoherentReport() throws Exception {
        MockMultipartFile file = xlsxFile("mixed.xlsx",
                new Object[]{"10.60.0.10", subnetId, null, null, "existing", null, false, null},
                new Object[]{"10.60.0.120", subnetId, null, null, "new-host", null, false, null},
                new Object[]{"999.0.0.1", subnetId, null, null, "bad-ip", null, false, null});

        mvc.perform(multipart("/api/v1/addresses/import/xlsx")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors").value(greaterThanOrEqualTo(1)));
    }

    private MockMultipartFile xlsxFile(String filename, Object[]... rows) throws Exception {
        return xlsxFileWithHeader(filename, STANDARD_HEADER, rows);
    }

    private MockMultipartFile xlsxFileWithHeader(String filename, String[] header, Object[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Addresses");

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                headerRow.createCell(i).setCellValue(header[i]);
            }

            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                Object[] values = rows[r];

                for (int c = 0; c < values.length; c++) {
                    Object value = values[c];
                    if (value == null) {
                        continue;
                    }

                    if (value instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (value instanceof Boolean b) {
                        row.createCell(c).setCellValue(b);
                    } else {
                        row.createCell(c).setCellValue(value.toString());
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);

            return new MockMultipartFile("file", filename, XLSX_CONTENT_TYPE, out.toByteArray());
        }
    }
}
