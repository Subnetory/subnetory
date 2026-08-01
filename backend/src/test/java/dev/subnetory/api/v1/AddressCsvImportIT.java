package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.*;
import org.junit.jupiter.api.*;
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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Import CSV adresses.
 *
 * <p>Couvre les 10 cas du cadrage Sprint 2.0 :</p>
 * <ol>
 *   <li>Import valide avec subnet_id</li>
 *   <li>Import valide avec subnet_network</li>
 *   <li>subnet_network ambigu (plusieurs subnets)</li>
 *   <li>IP invalide</li>
 *   <li>subnet_id inexistant</li>
 *   <li>subnet_id et subnet_network incohérents</li>
 *   <li>Fichier vide</li>
 *   <li>Header manquant</li>
 *   <li>override=true</li>
 *   <li>Import mixte — créations + erreurs + rapport correct</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressCsvImportIT {

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
    static Long subnetId;      // 10.40.0.0/24
    static Long subnet2Id;     // 10.40.1.0/24 — pour le test d'ambiguïté

    static final Long CTX_ID = 1L;

    @Test @Order(1) @DisplayName("Setup — auth + site + 2 subnets de test")
    void setup() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                .contentType("application/json")
                .content(om.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk()).andReturn();
        token = om.readValue(r.getResponse().getContentAsString(), TokenResponse.class).accessToken();

        MvcResult sr = mvc.perform(post("/api/v1/sites")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(new SiteRequest("Site CSV Import", "CSV-TEST", CTX_ID))))
                .andExpect(status().isCreated()).andReturn();
        siteId = om.readTree(sr.getResponse().getContentAsString()).get("id").asLong();

        // Subnet principal pour les tests
        MvcResult s1 = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new SubnetRequest("10.40.0.0/24", "Subnet CSV test", null, CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnetId = om.readTree(s1.getResponse().getContentAsString()).get("id").asLong();

        // Second site + subnet avec même réseau dans un autre site pour le test d'ambiguïté
        MvcResult sr2 = mvc.perform(post("/api/v1/sites")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(new SiteRequest("Site CSV Import 2", "CSV-TEST2", CTX_ID))))
                .andExpect(status().isCreated()).andReturn();
        Long siteId2 = om.readTree(sr2.getResponse().getContentAsString()).get("id").asLong();

        MvcResult s2 = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new SubnetRequest("10.40.0.0/24", "Subnet CSV ambiguité", null, CTX_ID, siteId2, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnet2Id = om.readTree(s2.getResponse().getContentAsString()).get("id").asLong();
    }

    // -------------------------------------------------------
    // Cas 1 — Import valide avec subnet_id
    // -------------------------------------------------------

    @Test @Order(2) @DisplayName("Cas 1 — import valide avec subnet_id → created=3")
    void import_validWithSubnetId_creates3() throws Exception {
        MockMultipartFile csv = csvFile("valid_with_subnet_id.csv", subnetId);

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.errors").value(0));
    }

    // -------------------------------------------------------
    // Cas 2 — Import valide avec subnet_network
    // -------------------------------------------------------

    @Test @Order(3) @DisplayName("Cas 2 — import valide avec subnet_network unique → created=1")
    void import_validWithSubnetNetwork_creates1() throws Exception {
        // On crée un site + subnet avec réseau unique pour ce test
        MvcResult sr3 = mvc.perform(post("/api/v1/sites")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(new SiteRequest("Site CSV Unique", "CSV-UNIQ", CTX_ID))))
                .andExpect(status().isCreated()).andReturn();
        Long site3 = om.readTree(sr3.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(om.writeValueAsString(
                        new SubnetRequest("10.50.0.0/24", "Unique network", null, CTX_ID, site3, null, null))))
                .andExpect(status().isCreated());

        String csvContent = "address,subnet_id,subnet_network,mac,hostname,description,temporary,discovery_source\n" +
                "10.50.0.5,,10.50.0.0/24,,srv-unique-01,Résolution réseau,,csv\n";

        MockMultipartFile csv = new MockMultipartFile(
                "file", "import.csv", "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors").value(0));
    }

    // -------------------------------------------------------
    // Cas 3 — subnet_network ambigu
    // -------------------------------------------------------

    @Test @Order(4) @DisplayName("Cas 3 — subnet_network ambigu → erreur ligne avec message clair")
    void import_ambiguousSubnetNetwork_reportsError() throws Exception {
        // 10.40.0.0/24 existe dans 2 sites (subnetId et subnet2Id)
        String csvContent = "address,subnet_id,subnet_network,mac,hostname\n" +
                "10.40.0.200,,10.40.0.0/24,,srv-ambigu\n";

        MockMultipartFile csv = new MockMultipartFile(
                "file", "import.csv", "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.errors").value(1))
                .andExpect(jsonPath("$.errorDetails[0].reason",
                        containsString("matches 2 subnets")));
    }

    // -------------------------------------------------------
    // Cas 4 — IP invalide
    // -------------------------------------------------------

    @Test @Order(5) @DisplayName("Cas 4 — IP invalide → erreur ligne")
    void import_invalidIp_reportsError() throws Exception {
        String csvContent = "address,subnet_id,mac,hostname\n" +
                "999.999.999.999," + subnetId + ",,bad-host\n";

        MockMultipartFile csv = new MockMultipartFile(
                "file", "import.csv", "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.errors").value(1));
    }

    // -------------------------------------------------------
    // Cas 5 — subnet_id inexistant
    // -------------------------------------------------------

    @Test @Order(6) @DisplayName("Cas 5 — subnet_id inexistant → erreur ligne")
    void import_unknownSubnetId_reportsError() throws Exception {
        String csvContent = "address,subnet_id,hostname\n" +
                "10.40.0.201,99999,srv-ghost\n";

        MockMultipartFile csv = new MockMultipartFile(
                "file", "import.csv", "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").value(1));
    }

    // -------------------------------------------------------
    // Cas 6 — subnet_id et subnet_network incohérents
    // -------------------------------------------------------

    @Test @Order(7) @DisplayName("Cas 6 — subnet_id et subnet_network incohérents → erreur ligne")
    void import_incoherentSubnetFields_reportsError() throws Exception {
        String csvContent = "address,subnet_id,subnet_network,hostname\n" +
                "10.40.0.202," + subnetId + ",192.168.99.0/24,srv-incoherent\n";

        MockMultipartFile csv = new MockMultipartFile(
                "file", "import.csv", "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").value(1))
                .andExpect(jsonPath("$.errorDetails[0].reason",
                        containsString("must match")));
    }

    // -------------------------------------------------------
    // Cas 7 — Fichier vide
    // -------------------------------------------------------

    @Test @Order(8) @DisplayName("Cas 7 — fichier vide → 400")
    void import_emptyFile_returns400() throws Exception {
        MockMultipartFile csv = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------
    // Cas 8 — Header manquant (colonne 'address' absente)
    // -------------------------------------------------------

    @Test @Order(9) @DisplayName("Cas 8 — header sans colonne 'address' → 400")
    void import_missingAddressColumn_returns400() throws Exception {
        String csvContent = "hostname,subnet_id\nsrv-noheader," + subnetId + "\n";

        MockMultipartFile csv = new MockMultipartFile(
                "file", "import.csv", "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("address")));
    }

    // -------------------------------------------------------
    // Cas 9 — override=true
    // -------------------------------------------------------

    @Test @Order(10) @DisplayName("Cas 9 — override=true met à jour hostname existant")
    void import_override_updatesExisting() throws Exception {
        // 10.40.0.10 a été créé au Cas 1 avec hostname srv-import-01
        String csvContent = "address,subnet_id,hostname\n" +
                "10.40.0.10," + subnetId + ",srv-import-01-updated\n";

        MockMultipartFile csv = new MockMultipartFile(
                "file", "import.csv", "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .param("override", "true")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedLastSeen").value(greaterThanOrEqualTo(0)));

        // Vérifier que le hostname a bien changé
        mvc.perform(get("/api/v1/addresses/by-ip/10.40.0.10")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("srv-import-01-updated"));
    }

    // -------------------------------------------------------
    // Cas 10 — Import mixte
    // -------------------------------------------------------

    @Test @Order(11) @DisplayName("Cas 10 — import mixte : créations + existants + erreurs → rapport correct")
    void import_mixed_returnsCorrectReport() throws Exception {
        // 10.40.0.10 existe déjà (créé au Cas 1), 10.40.0.110 est nouveau, 999.x est invalide
        String csvContent = "address,subnet_id,hostname\n" +
                "10.40.0.10," + subnetId + ",existing\n" +          // IP existante → skipped
                "10.40.0.110," + subnetId + ",srv-new-01\n" +        // IP nouvelle → created
                "999.0.0.1," + subnetId + ",bad-ip\n";               // IP invalide → error

        MockMultipartFile csv = new MockMultipartFile(
                "file", "import.csv", "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/addresses/import/csv")
                .file(csv)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.errors").value(greaterThanOrEqualTo(1)));
    }

    // -------------------------------------------------------
    // Helper
    // -------------------------------------------------------

    private MockMultipartFile csvFile(String filename, Long resolvedSubnetId) throws Exception {
        InputStream raw = getClass().getResourceAsStream("/csv/" + filename);
        if (raw == null) throw new IllegalArgumentException("Fixture not found: " + filename);
        String content = new String(raw.readAllBytes(), StandardCharsets.UTF_8)
                .replace("__SUBNET_ID__", resolvedSubnetId.toString());
        return new MockMultipartFile("file", filename, "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
