package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.dto.*;
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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration Sprint 1.1 — API search, PATCH, upsert, bulk-upsert.
 *
 * Couvre les 11 cas définis dans le cadrage :
 *  1.  Recherche par hostname exact
 *  2.  Recherche par hostnameContains
 *  3.  Recherche par MAC
 *  4.  Recherche multi-critères (q + siteId)
 *  5.  by-hostname retourne liste
 *  6.  PATCH partiel — champ présent modifié
 *  7.  PATCH partiel — champ absent non modifié
 *  8.  PATCH null — champ nullable vidé
 *  9.  Upsert par IP — création (IP absente)
 * 10.  Upsert par IP — last_seen_at mis à jour (IP existante, sans override)
 * 11.  Upsert par IP — override=true écrase les champs
 * 12.  Bulk-upsert — création pour IP absente
 * 13.  Bulk-upsert — IP existante sans override → skipped, last_seen_at mis à jour
 * 14.  Bulk-upsert — IP existante avec override=true → updated
 * 15.  Bulk-upsert — discovery_source conservé sur entrée existante
 * 16.  discovery_source invalide → 400
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressSearchAndUpsertIT {

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
    // Adresses créées pour les tests de recherche et PATCH
    static Long addrSrvId;       // srv-web-001 / 10.20.0.10
    static Long addrDbId;        // srv-db-001  / 10.20.0.20

    static final Long CTX_ID = 1L;

    @Test @Order(1) @DisplayName("Setup — auth + site + subnet + 2 adresses")
    void setup() throws Exception {
        // Auth
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk()).andReturn();
        token = om.readValue(r.getResponse().getContentAsString(), TokenResponse.class).accessToken();

        // Site
        MvcResult sr = mvc.perform(post("/api/v1/sites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new SiteRequest("Site Search IT", "SSI-TEST", CTX_ID))))
                .andExpect(status().isCreated()).andReturn();
        siteId = om.readTree(sr.getResponse().getContentAsString()).get("id").asLong();

        // Subnet
        MvcResult snr = mvc.perform(post("/api/v1/subnets")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(
                        new SubnetRequest("10.20.0.0/24", "Search test subnet",
                                "10.20.0.1", CTX_ID, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnetId = om.readTree(snr.getResponse().getContentAsString()).get("id").asLong();

        // Adresse 1 — srv-web-001, source=manual
        MvcResult a1 = mvc.perform(post("/api/v1/addresses")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AddressRequest(
                        "10.20.0.10", "aa:bb:cc:dd:ee:01", "srv-web-001",
                        "Serveur web principal", subnetId, false, "manual"))))
                .andExpect(status().isCreated()).andReturn();
        addrSrvId = om.readTree(a1.getResponse().getContentAsString()).get("id").asLong();

        // Adresse 2 — srv-db-001, source=api
        MvcResult a2 = mvc.perform(post("/api/v1/addresses")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AddressRequest(
                        "10.20.0.20", "aa:bb:cc:dd:ee:02", "srv-db-001",
                        "Base de données production", subnetId, false, "api"))))
                .andExpect(status().isCreated()).andReturn();
        addrDbId = om.readTree(a2.getResponse().getContentAsString()).get("id").asLong();
    }

    // -------------------------------------------------------
    // Cas 1 — Recherche par hostname exact
    // -------------------------------------------------------
    @Test @Order(2) @DisplayName("Cas 1 — ?hostname=srv-web-001 retourne 1 résultat exact")
    void search_byHostnameExact() throws Exception {
        mvc.perform(get("/api/v1/addresses").param("hostname", "srv-web-001")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].hostname").value("srv-web-001"));
    }

    // -------------------------------------------------------
    // Cas 2 — Recherche hostnameContains
    // -------------------------------------------------------
    @Test @Order(3) @DisplayName("Cas 2 — ?hostnameContains=srv retourne les 2 serveurs")
    void search_byHostnameContains() throws Exception {
        mvc.perform(get("/api/v1/addresses").param("hostnameContains", "srv")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    // -------------------------------------------------------
    // Cas 3 — Recherche par MAC
    // -------------------------------------------------------
    @Test @Order(4) @DisplayName("Cas 3 — ?mac= retourne l'adresse correspondante")
    void search_byMac() throws Exception {
        mvc.perform(get("/api/v1/addresses").param("mac", "aa:bb:cc:dd:ee:02")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].hostname").value("srv-db-001"));
    }

    // -------------------------------------------------------
    // Cas 4 — Recherche multi-critères
    // -------------------------------------------------------
    @Test @Order(5) @DisplayName("Cas 4 — ?q=web&siteId= retourne srv-web-001 seulement")
    void search_multiCriteria() throws Exception {
        mvc.perform(get("/api/v1/addresses")
                .param("q", "web")
                .param("siteId", siteId.toString())
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].hostname").value("srv-web-001"));
    }

    // -------------------------------------------------------
    // Cas 5 — by-hostname retourne liste
    // -------------------------------------------------------
    @Test @Order(6) @DisplayName("Cas 5 — /by-hostname/srv-web-001 retourne liste avec 1 IP")
    void getByHostname_returnsList() throws Exception {
        mvc.perform(get("/api/v1/addresses/by-hostname/srv-web-001")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].address").value("10.20.0.10"));
    }

    // -------------------------------------------------------
    // Cas 6 — PATCH champ présent modifié
    // -------------------------------------------------------
    @Test @Order(7) @DisplayName("Cas 6 — PATCH hostname modifié (champ présent)")
    void patch_presentField_isModified() throws Exception {
        mvc.perform(patch("/api/v1/addresses/" + addrSrvId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("hostname", "srv-web-001-renamed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("srv-web-001-renamed"));
    }

    // -------------------------------------------------------
    // Cas 7 — PATCH champ absent non modifié
    // -------------------------------------------------------
    @Test @Order(8) @DisplayName("Cas 7 — PATCH sans 'mac' ne touche pas à la MAC existante")
    void patch_absentField_notModified() throws Exception {
        // On patch uniquement la description, la MAC doit rester intacte
        mvc.perform(patch("/api/v1/addresses/" + addrSrvId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("description", "Description mise à jour"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Description mise à jour"))
                .andExpect(jsonPath("$.mac").value("aa:bb:cc:dd:ee:01")); // inchangée
    }

    // -------------------------------------------------------
    // Cas 8 — PATCH null vide le champ nullable
    // -------------------------------------------------------
    @Test @Order(9) @DisplayName("Cas 8 — PATCH mac=null vide le champ MAC (nullable)")
    void patch_nullField_clearsNullable() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("mac", null);
        mvc.perform(patch("/api/v1/addresses/" + addrDbId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mac").value(nullValue()));
    }

    // -------------------------------------------------------
    // Cas 9 — Upsert par IP — création (IP absente)
    // -------------------------------------------------------
    @Test @Order(10) @DisplayName("Cas 9 — PUT /by-ip/10.20.0.50 crée l'entrée (absente)")
    void upsertByIp_creates_whenAbsent() throws Exception {
        AddressUpsertRequest body = new AddressUpsertRequest(
                subnetId, "00:11:22:33:44:55", "pc-accueil-01",
                "Découvert scan", false, "nmap");
        mvc.perform(put("/api/v1/addresses/by-ip/10.20.0.50")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("10.20.0.50"))
                .andExpect(jsonPath("$.discoverySource").value("nmap"))
                .andExpect(jsonPath("$.lastSeenAt").isNotEmpty());
    }

    // -------------------------------------------------------
    // Cas 10 — Upsert par IP — IP existante, sans override
    // -------------------------------------------------------
    @Test @Order(11)
    @DisplayName("Cas 10 — PUT /by-ip/10.20.0.50 sans override : seul last_seen_at change")
    void upsertByIp_existingNoOverride_onlyLastSeenUpdated() throws Exception {
        AddressUpsertRequest body = new AddressUpsertRequest(
                subnetId, "ff:ff:ff:ff:ff:ff", "pc-accueil-RENAMED",
                "Nouveau scan", false, "arp-scan");
        mvc.perform(put("/api/v1/addresses/by-ip/10.20.0.50")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                // Hostname et MAC inchangés (pas d'override)
                .andExpect(jsonPath("$.hostname").value("pc-accueil-01"))
                .andExpect(jsonPath("$.mac").value("00:11:22:33:44:55"))
                // last_seen_at mis à jour
                .andExpect(jsonPath("$.lastSeenAt").isNotEmpty())
                // discovery_source conservé (nmap, pas arp-scan)
                .andExpect(jsonPath("$.discoverySource").value("nmap"));
    }

    // -------------------------------------------------------
    // Cas 11 — Upsert par IP — override=true écrase les champs
    // -------------------------------------------------------
    @Test @Order(12)
    @DisplayName("Cas 11 — PUT /by-ip/10.20.0.50 override=true écrase hostname et MAC")
    void upsertByIp_override_updatesFields() throws Exception {
        AddressUpsertRequest body = new AddressUpsertRequest(
                subnetId, "ff:ff:ff:ff:ff:ff", "pc-accueil-OVERRIDE",
                null, false, null);
        mvc.perform(put("/api/v1/addresses/by-ip/10.20.0.50")
                .param("override", "true")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("pc-accueil-OVERRIDE"))
                .andExpect(jsonPath("$.mac").value("ff:ff:ff:ff:ff:ff"))
                // discovery_source TOUJOURS conservé même avec override
                .andExpect(jsonPath("$.discoverySource").value("nmap"));
    }

    // -------------------------------------------------------
    // Cas 12 — Bulk-upsert création IP absente
    // -------------------------------------------------------
    @Test @Order(13) @DisplayName("Cas 12 — bulk-upsert crée les IPs absentes")
    void bulkUpsert_createsAbsentIps() throws Exception {
        BulkUpsertRequest body = new BulkUpsertRequest(
                List.of(
                    new BulkUpsertRequest.BulkUpsertEntry(
                        "10.20.0.100", subnetId, null, "printer-rdc", "Imprimante RDC", false, "nmap"),
                    new BulkUpsertRequest.BulkUpsertEntry(
                        "10.20.0.101", subnetId, null, "printer-r1", "Imprimante R1", false, "nmap")
                ),
                false
        );
        mvc.perform(post("/api/v1/addresses/bulk-upsert")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }

    // -------------------------------------------------------
    // Cas 13 — Bulk-upsert IP existante sans override → skipped
    // -------------------------------------------------------
    @Test @Order(14)
    @DisplayName("Cas 13 — bulk-upsert IP existante sans override → skipped, last_seen_at mis à jour")
    void bulkUpsert_existingNoOverride_isSkipped() throws Exception {
        BulkUpsertRequest body = new BulkUpsertRequest(
                List.of(new BulkUpsertRequest.BulkUpsertEntry(
                    "10.20.0.100", subnetId, "99:99:99:99:99:99",
                    "RENAMED", "RENAMED", false, "dns")),
                false
        );
        mvc.perform(post("/api/v1/addresses/bulk-upsert")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(1));

        // Vérifier que le hostname est resté intact
        mvc.perform(get("/api/v1/addresses/by-ip/10.20.0.100")
                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.hostname").value("printer-rdc"))
                .andExpect(jsonPath("$.lastSeenAt").isNotEmpty());
    }

    // -------------------------------------------------------
    // Cas 14 — Bulk-upsert avec override=true → updated
    // -------------------------------------------------------
    @Test @Order(15)
    @DisplayName("Cas 14 — bulk-upsert override=true met à jour les champs")
    void bulkUpsert_override_updatesExisting() throws Exception {
        BulkUpsertRequest body = new BulkUpsertRequest(
                List.of(new BulkUpsertRequest.BulkUpsertEntry(
                    "10.20.0.101", subnetId, "11:22:33:44:55:66",
                    "printer-r1-updated", "Override test", false, "csv")),
                true
        );
        mvc.perform(post("/api/v1/addresses/bulk-upsert")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        mvc.perform(get("/api/v1/addresses/by-ip/10.20.0.101")
                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.hostname").value("printer-r1-updated"))
                .andExpect(jsonPath("$.mac").value("11:22:33:44:55:66"))
                // discovery_source toujours conservé (nmap, pas csv)
                .andExpect(jsonPath("$.discoverySource").value("nmap"));
    }

    // -------------------------------------------------------
    // Cas 15 — discovery_source conservé sur entrée existante
    // (vérifié dans les cas 10, 11, 13, 14 — test de synthèse)
    // -------------------------------------------------------
    @Test @Order(16)
    @DisplayName("Cas 15 — discovery_source jamais modifié sur entrée existante")
    void discoverySource_neverModifiedOnExisting() throws Exception {
        // srv-db-001 a été créé avec source="api"
        // On tente de le mettre à jour avec source="csv" via PUT
        mvc.perform(put("/api/v1/addresses/" + addrDbId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AddressRequest(
                        "10.20.0.20", null, "srv-db-001", "modifié",
                        subnetId, false, "csv"))))
                .andExpect(status().isOk())
                // discovery_source doit rester "api" (défini à la création)
                .andExpect(jsonPath("$.discoverySource").value("api"));
    }

    // -------------------------------------------------------
    // Cas 16 — discovery_source invalide → 400
    // -------------------------------------------------------
    @Test @Order(17)
    @DisplayName("Cas 16 — discovery_source invalide → 400 Bad Request")
    void discoverySource_invalid_returns400() throws Exception {
        mvc.perform(post("/api/v1/addresses")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AddressRequest(
                        "10.20.0.200", null, null, null,
                        subnetId, false, "invalid-source"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }
}

