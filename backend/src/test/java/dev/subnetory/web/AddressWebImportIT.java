package dev.subnetory.web;

import dev.subnetory.config.SecurityConfig;
import dev.subnetory.csv.CsvParseException;
import dev.subnetory.dto.CsvImportResponse;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.util.ImportFileValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AddressWebController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, ImportFileValidator.class})
class AddressWebImportIT {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @MockitoBean AddressService addressService;
    @MockitoBean SubnetService subnetService;
    @MockitoBean IpAllocService ipAllocService;
    @MockitoBean ActiveContextService activeContextService;
    @MockitoBean AuthAuditService authAuditService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean SubnetoryUserDetailsService userDetailsService;

    // Beans ajoutes par Sprint 2.13 / T4.
    // Necessaires ici car @WebMvcTest ne charge pas tout le contexte applicatif.
    @MockitoBean LoginRateLimiter loginRateLimiter;
    @MockitoBean ApiRateLimiter apiRateLimiter;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean RateLimitingAuthenticationFailureHandler failureHandler;
    @MockitoBean RateLimitingAuthenticationSuccessHandler successHandler;

    @BeforeEach
    void setUp() {
        when(activeContextService.get(any())).thenReturn(1L);
    }

    @Test
    void importCsv_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(multipart("/network/addresses/import/csv")
                        .file(csvFile())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verify(addressService, never()).importCsv(any(InputStream.class), eq(false), any(), any());
    }

    @Test
    @WithMockUser(roles = "IP")
    void importCsv_withoutCsrf_returns403() throws Exception {
        mvc.perform(multipart("/network/addresses/import/csv")
                        .file(csvFile()))
                .andExpect(status().isForbidden());

        verify(addressService, never()).importCsv(any(InputStream.class), eq(false), any(), any());
    }

    @Test
    @WithMockUser(username = "ipuser", roles = "IP")
    void importCsv_withSession_redirectsToResult() throws Exception {
        CsvImportResponse response = successResponse();

        when(addressService.importCsv(any(InputStream.class), eq(false), eq("ipuser"), eq(1L)))
                .thenReturn(response);

        mvc.perform(multipart("/network/addresses/import/csv")
                        .file(csvFile())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses/import-result"))
                .andExpect(flash().attribute("importResult", response))
                .andExpect(flash().attribute("importFormat", "CSV"))
                .andExpect(flash().attribute("flashSuccess", "Import CSV termine."));

        verify(addressService).importCsv(any(InputStream.class), eq(false), eq("ipuser"), eq(1L));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void importXlsx_withSession_redirectsToResult() throws Exception {
        CsvImportResponse response = successResponse();

        when(addressService.importXlsx(any(InputStream.class), eq(true), eq("admin"), eq(1L)))
                .thenReturn(response);

        mvc.perform(multipart("/network/addresses/import/xlsx")
                        .file(xlsxFile())
                        .param("override", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses/import-result"))
                .andExpect(flash().attribute("importResult", response))
                .andExpect(flash().attribute("importFormat", "XLSX"))
                .andExpect(flash().attribute("flashSuccess", "Import XLSX termine."));

        verify(addressService).importXlsx(any(InputStream.class), eq(true), eq("admin"), eq(1L));
    }

    @Test
    @WithMockUser(username = "ipuser", roles = "IP")
    void importCsv_withoutActiveContext_redirectsWithFlashError() throws Exception {
        when(activeContextService.get(any())).thenReturn(null);

        mvc.perform(multipart("/network/addresses/import/csv")
                        .file(csvFile())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses/import"))
                .andExpect(flash().attribute("flashError", "Sélectionnez un contexte actif avant d'importer."));

        verify(addressService, never()).importCsv(any(InputStream.class), eq(false), any(), any());
    }

    @Test
    @WithMockUser(username = "ipuser", roles = "IP")
    void importCsv_emptyFile_redirectsWithFlashError() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);

        mvc.perform(multipart("/network/addresses/import/csv")
                        .file(empty)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses/import"))
                .andExpect(flash().attribute("flashError", "Fichier CSV vide."));

        verify(addressService, never()).importCsv(any(InputStream.class), eq(false), any(), any());
    }

    @Test
    @WithMockUser(username = "ipuser", roles = "IP")
    void importXlsx_emptyFile_redirectsWithFlashError() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.xlsx", XLSX_MIME, new byte[0]);

        mvc.perform(multipart("/network/addresses/import/xlsx")
                        .file(empty)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses/import"))
                .andExpect(flash().attribute("flashError", "Fichier XLSX vide."));

        verify(addressService, never()).importXlsx(any(InputStream.class), eq(false), any(), any());
    }

    @Test
    @WithMockUser(username = "ipuser", roles = "IP")
    void importCsv_parseError_redirectsWithFlashError() throws Exception {
        when(addressService.importCsv(any(InputStream.class), eq(false), eq("ipuser"), eq(1L)))
                .thenThrow(new CsvParseException("Missing required column: address"));

        mvc.perform(multipart("/network/addresses/import/csv")
                        .file(csvFile())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses/import"))
                .andExpect(flash().attribute("flashError",
                        containsString("Import CSV impossible")));

        verify(addressService).importCsv(any(InputStream.class), eq(false), eq("ipuser"), eq(1L));
    }

    @Test
    void importResult_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/addresses/import-result"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void importResult_withoutFlash_redirectsToAddresses() throws Exception {
        mvc.perform(get("/network/addresses/import-result"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses/import"))
                .andExpect(flash().attribute("flashError", "Aucun rapport d'import disponible."));
    }

    @Test
    @WithMockUser(roles = "IP")
    void importResult_withFlash_returnsView() throws Exception {
        CsvImportResponse response = successResponse();

        mvc.perform(get("/network/addresses/import-result")
                        .flashAttr("importResult", response)
                        .flashAttr("importFormat", "CSV"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/import-result"))
                .andExpect(model().attribute("importResult", response))
                .andExpect(model().attribute("importFormat", "CSV"))
                .andExpect(model().attribute("canManage", true))
                .andExpect(model().attribute("activeSection", "addresses"))
                .andExpect(model().attribute("pageTitle", "Rapport d'import"));
    }

    private MockMultipartFile csvFile() {
        String body = """
                address,subnet_id,hostname
                192.168.1.10,1,host-01
                """;
        return new MockMultipartFile("file", "addresses.csv", "text/csv", body.getBytes());
    }

    private MockMultipartFile xlsxFile() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Adresses");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("address");
            header.createCell(1).setCellValue("subnet_id");
            header.createCell(2).setCellValue("hostname");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("192.168.1.10");
            row.createCell(1).setCellValue(1);
            row.createCell(2).setCellValue("host-01");
            workbook.write(out);
            return new MockMultipartFile("file", "addresses.xlsx", XLSX_MIME, out.toByteArray());
        }
    }

    private CsvImportResponse successResponse() {
        return new CsvImportResponse(
                1,
                1,
                0,
                0,
                0,
                List.of());
    }
}
