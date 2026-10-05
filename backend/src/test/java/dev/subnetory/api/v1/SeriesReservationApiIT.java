package dev.subnetory.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.subnetory.domain.AuthAuditLog;
import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesReservationRequest;
import dev.subnetory.dto.SeriesRowsRequest;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.dto.TokenResponse;
import dev.subnetory.repository.AuthAuditLogRepository;
import dev.subnetory.service.AuthAuditService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests d'integration de la reservation d'adresses en serie
 * ({@code /api/v1/addresses/series}) : apercu sans ecriture, creation atomique,
 * conflits (409 + detail ligne par ligne), mode skip, validation et audit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeriesReservationApiIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres:17-alpine"))
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
    @Autowired AuthAuditLogRepository authAuditLogRepository;

    static String adminToken;
    static Long subnetId;

    static final Long SEED_CONTEXT_ID = 1L; // contexte "Default" cree par V3
    static final String SERIES_URL = "/api/v1/addresses/series";

    // -------------------------------------------------------
    // Setup
    // -------------------------------------------------------

    @Test @Order(1)
    @DisplayName("Setup - auth admin")
    void setup_auth() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk()).andReturn();
        adminToken = objectMapper.readValue(r.getResponse().getContentAsString(), TokenResponse.class).accessToken();
    }

    @Test @Order(2)
    @DisplayName("Setup - creer site + subnet de test")
    void setup_siteAndSubnet() throws Exception {
        MvcResult sr = mvc.perform(post("/api/v1/sites")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SiteRequest("Site Series Test", "SER-API", SEED_CONTEXT_ID))))
                .andExpect(status().isCreated()).andReturn();
        Long siteId = objectMapper.readTree(sr.getResponse().getContentAsString()).get("id").asLong();

        MvcResult snr = mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("172.18.0.0/24", "Subnet series test",
                                        "172.18.0.1", SEED_CONTEXT_ID, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnetId = objectMapper.readTree(snr.getResponse().getContentAsString()).get("id").asLong();
    }

    // -------------------------------------------------------
    // Apercu
    // -------------------------------------------------------

    @Test @Order(3)
    @DisplayName("POST /series/preview - calcule la serie sans rien ecrire")
    void preview_returnsSeriesWithoutWriting() throws Exception {
        mvc.perform(post(SERIES_URL + "/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("172.18.0.21", 3, "NTNX-HOST-", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("block")))
                .andExpect(jsonPath("$.canCreate", is(true)))
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].ip", is("172.18.0.21")))
                .andExpect(jsonPath("$.rows[0].hostname", is("NTNX-HOST-01")))
                .andExpect(jsonPath("$.rows[2].ip", is("172.18.0.23")))
                .andExpect(jsonPath("$.rows[2].hostname", is("NTNX-HOST-03")))
                .andExpect(jsonPath("$.rows[0].status", is("AVAILABLE")));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    // -------------------------------------------------------
    // Creation
    // -------------------------------------------------------

    @Test @Order(4)
    @DisplayName("POST /series - cree toute la serie (201) et la trace dans l'audit")
    void create_persistsSeriesAndAudits() throws Exception {
        mvc.perform(post(SERIES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("172.18.0.21", 3, "NTNX-HOST-", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canCreate", is(true)))
                .andExpect(jsonPath("$.rows", hasSize(3)));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "NTNX-HOST-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));

        List<AuthAuditLog> logs = authAuditLogRepository.findByEventType(
                AuthAuditService.ADDRESS_SERIES_RESERVED, Pageable.unpaged()).getContent();
        assertThat(logs).anyMatch(log ->
                "admin".equals(log.getUsername())
                        && log.getMessage() != null
                        && log.getMessage().contains("subnetId=" + subnetId)
                        && log.getMessage().contains("count=3")
                        && log.getMessage().contains("first=172.18.0.21")
                        && log.getMessage().contains("last=172.18.0.23"));
    }

    @Test @Order(5)
    @DisplayName("POST /series - adresses deja utilisees -> 409 avec detail, rien de cree")
    void create_conflictReturns409WithPreviewAndCreatesNothing() throws Exception {
        mvc.perform(post(SERIES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("172.18.0.22", 3, "NTNX-CVM-", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.preview.canCreate", is(false)))
                .andExpect(jsonPath("$.preview.rows[0].status", is("OCCUPIED")))
                .andExpect(jsonPath("$.preview.rows[1].status", is("OCCUPIED")))
                .andExpect(jsonPath("$.preview.rows[2].status", is("AVAILABLE")));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "NTNX-CVM-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test @Order(6)
    @DisplayName("POST /series - mode skip saute les adresses occupees")
    void create_skipModeSkipsOccupiedAddresses() throws Exception {
        mvc.perform(post(SERIES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("172.18.0.21", 2, "NTNX-CVM-", "skip")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode", is("skip")))
                .andExpect(jsonPath("$.skipped", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].ip", is("172.18.0.24")))
                .andExpect(jsonPath("$.rows[0].hostname", is("NTNX-CVM-01")))
                .andExpect(jsonPath("$.rows[1].ip", is("172.18.0.25")))
                .andExpect(jsonPath("$.rows[1].hostname", is("NTNX-CVM-02")));
    }

    @Test @Order(7)
    @DisplayName("POST /series - depart hors sous-reseau -> 409 OUT_OF_RANGE")
    void create_startOutsideSubnetReturns409() throws Exception {
        mvc.perform(post(SERIES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("172.19.0.5", 2, "OUT-", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.preview.rows[0].status", is("OUT_OF_RANGE")));
    }

    // -------------------------------------------------------
    // Validation et securite
    // -------------------------------------------------------

    @Test @Order(8)
    @DisplayName("POST /series - quantite 0 -> 400 (Bean Validation)")
    void create_zeroCountReturns400() throws Exception {
        mvc.perform(post(SERIES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("172.18.0.40", 0, "X-", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.count").exists());
    }

    @Test @Order(9)
    @DisplayName("POST /series - IP de depart invalide -> 400 (Bean Validation)")
    void create_invalidStartIpReturns400() throws Exception {
        mvc.perform(post(SERIES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("999.1.1.1", 2, "X-", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.startIp").exists());
    }

    @Test @Order(10)
    @DisplayName("POST /series - nom d'hote de plus de 100 caracteres -> 400 invalid-series")
    void create_hostnameTooLongReturns400() throws Exception {
        SeriesReservationRequest request = new SeriesReservationRequest(
                subnetId, "172.18.0.60", 2, null, "p".repeat(90), null, 9, "sssss", null, null, null);

        mvc.perform(post(SERIES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Invalid Series")));
    }

    @Test @Order(11)
    @DisplayName("POST /series - sans authentification -> 401")
    void create_withoutTokenReturns401() throws Exception {
        mvc.perform(post(SERIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("172.18.0.80", 2, "X-", null)))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------
    // Lignes explicites (/rows)
    // -------------------------------------------------------

    @Test @Order(12)
    @DisplayName("POST /series/rows/preview - statut de chaque ligne, sans rien ecrire")
    void previewRows_returnsStatusPerRow() throws Exception {
        mvc.perform(post(SERIES_URL + "/rows/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rows(row("172.18.0.100", "IPMI-01"), row("172.18.0.22", "x"),
                                row("172.18.0.1", "gw"), row("172.19.0.5", "far"),
                                row("zzz", "bad"), row("172.18.0.100", "dup"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("rows")))
                .andExpect(jsonPath("$.canCreate", is(false)))
                .andExpect(jsonPath("$.rows[0].status", is("AVAILABLE")))
                .andExpect(jsonPath("$.rows[1].status", is("OCCUPIED")))
                .andExpect(jsonPath("$.rows[2].status", is("RESERVED")))
                .andExpect(jsonPath("$.rows[3].status", is("OUT_OF_RANGE")))
                .andExpect(jsonPath("$.rows[4].status", is("INVALID")))
                .andExpect(jsonPath("$.rows[5].status", is("DUPLICATE")));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "IPMI-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test @Order(13)
    @DisplayName("POST /series/rows - cree les lignes (201) avec leurs attributs et les trace dans l'audit")
    void createRows_persistsRowsAndAudits() throws Exception {
        mvc.perform(post(SERIES_URL + "/rows")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rows(
                                new SeriesRowsRequest.Row("172.18.0.100", "IPMI-01", "BMC 1", "AA:BB:CC:00:00:01", false),
                                row("172.18.0.105", "IPMI-02"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canCreate", is(true)))
                .andExpect(jsonPath("$.rows", hasSize(2)));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "IPMI-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));

        List<AuthAuditLog> logs = authAuditLogRepository.findByEventType(
                AuthAuditService.ADDRESS_SERIES_RESERVED, Pageable.unpaged()).getContent();
        assertThat(logs).anyMatch(log ->
                "admin".equals(log.getUsername())
                        && log.getMessage() != null
                        && log.getMessage().contains("count=2")
                        && log.getMessage().contains("first=172.18.0.100")
                        && log.getMessage().contains("last=172.18.0.105"));
    }

    @Test @Order(14)
    @DisplayName("POST /series/rows - une ligne occupee -> 409 avec detail, rien de cree")
    void createRows_conflictReturns409AndCreatesNothing() throws Exception {
        mvc.perform(post(SERIES_URL + "/rows")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rows(row("172.18.0.106", "NOPE-01"), row("172.18.0.100", "NOPE-02"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.preview.canCreate", is(false)))
                .andExpect(jsonPath("$.preview.rows[0].status", is("AVAILABLE")))
                .andExpect(jsonPath("$.preview.rows[1].status", is("OCCUPIED")));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "NOPE-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test @Order(15)
    @DisplayName("POST /series/rows - liste vide -> 400 (Bean Validation)")
    void createRows_emptyListReturns400() throws Exception {
        mvc.perform(post(SERIES_URL + "/rows")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rows()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.rows").exists());
    }

    @Test @Order(16)
    @DisplayName("POST /series/rows - MAC invalide -> 400 (Bean Validation)")
    void createRows_invalidMacReturns400() throws Exception {
        mvc.perform(post(SERIES_URL + "/rows")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rows(new SeriesRowsRequest.Row("172.18.0.110", "M-01", null, "not-a-mac", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields").exists());
    }

    @Test @Order(17)
    @DisplayName("POST /series/rows - sans authentification -> 401")
    void createRows_withoutTokenReturns401() throws Exception {
        mvc.perform(post(SERIES_URL + "/rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rows(row("172.18.0.120", "X-01"))))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------
    // Series enchainees (/groups)
    // -------------------------------------------------------

    @Test @Order(18)
    @DisplayName("POST /series/groups/preview - groupes enchaines avec ecart, sans rien ecrire")
    void previewGroups_chainsGroupsWithGap() throws Exception {
        mvc.perform(post(SERIES_URL + "/groups/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(groups("172.18.0.130", 1, null,
                                group("HOST", 2, "GH-"), group("CVM", 2, "GC-"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode", is("block")))
                .andExpect(jsonPath("$.canCreate", is(true)))
                .andExpect(jsonPath("$.requested", is(4)))
                .andExpect(jsonPath("$.groups", hasSize(2)))
                .andExpect(jsonPath("$.groups[0].label", is("HOST")))
                .andExpect(jsonPath("$.groups[0].rows[0].ip", is("172.18.0.130")))
                .andExpect(jsonPath("$.groups[0].rows[1].ip", is("172.18.0.131")))
                .andExpect(jsonPath("$.groups[1].rows[0].ip", is("172.18.0.133")))
                .andExpect(jsonPath("$.groups[1].rows[0].hostname", is("GC-01")))
                .andExpect(jsonPath("$.groups[1].rows[1].ip", is("172.18.0.134")));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "GH-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test @Order(19)
    @DisplayName("POST /series/groups - cree tous les groupes (201) et les trace dans l'audit")
    void createGroups_persistsAllGroupsAndAudits() throws Exception {
        mvc.perform(post(SERIES_URL + "/groups")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(groups("172.18.0.130", 1, null,
                                group("HOST", 2, "GH-"), group("CVM", 2, "GC-"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canCreate", is(true)))
                .andExpect(jsonPath("$.groups", hasSize(2)));

        for (String prefix : List.of("GH-", "GC-")) {
            mvc.perform(get("/api/v1/addresses")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("subnetId", String.valueOf(subnetId))
                            .param("hostnameContains", prefix))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)));
        }

        List<AuthAuditLog> logs = authAuditLogRepository.findByEventType(
                AuthAuditService.ADDRESS_SERIES_RESERVED, Pageable.unpaged()).getContent();
        assertThat(logs).anyMatch(log ->
                "admin".equals(log.getUsername())
                        && log.getMessage() != null
                        && log.getMessage().contains("count=4")
                        && log.getMessage().contains("first=172.18.0.130")
                        && log.getMessage().contains("last=172.18.0.134"));
    }

    @Test @Order(20)
    @DisplayName("POST /series/groups - un groupe en conflit -> 409 avec detail par groupe, rien de cree")
    void createGroups_conflictReturns409AndCreatesNothing() throws Exception {
        mvc.perform(post(SERIES_URL + "/groups")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(groups("172.18.0.131", 0, null,
                                group("A", 1, "XA-"), group("B", 1, "XB-"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.preview.canCreate", is(false)))
                .andExpect(jsonPath("$.preview.groups[0].rows[0].status", is("OCCUPIED")))
                .andExpect(jsonPath("$.preview.groups[1].rows[0].status", is("AVAILABLE")));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "XB-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test @Order(21)
    @DisplayName("POST /series/groups - groupes absents ou nombre invalide -> 400")
    void createGroups_invalidRequestsReturn400() throws Exception {
        mvc.perform(post(SERIES_URL + "/groups")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(groups("172.18.0.150", 0, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.groups").exists());

        mvc.perform(post(SERIES_URL + "/groups")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(groups("172.18.0.150", 0, null, group("A", 0, "A-"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields").exists());
    }

    @Test @Order(22)
    @DisplayName("POST /series/groups - sans authentification -> 401")
    void createGroups_withoutTokenReturns401() throws Exception {
        mvc.perform(post(SERIES_URL + "/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(groups("172.18.0.160", 0, null, group("A", 1, "A-"))))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private static SeriesRowsRequest.Row row(String ip, String hostname) {
        return new SeriesRowsRequest.Row(ip, hostname, null, null, null);
    }

    private String rows(SeriesRowsRequest.Row... rows) throws Exception {
        return objectMapper.writeValueAsString(new SeriesRowsRequest(subnetId, List.of(rows)));
    }

    private static SeriesGroupsRequest.Group group(String label, int count, String prefix) {
        return new SeriesGroupsRequest.Group(label, count, null, prefix, null, null, null);
    }

    private String groups(String startIp, int gap, String occupiedMode,
                          SeriesGroupsRequest.Group... groups) throws Exception {
        return objectMapper.writeValueAsString(new SeriesGroupsRequest(
                subnetId, startIp, gap, occupiedMode, null, null, List.of(groups)));
    }

    private String body(String startIp, int count, String prefix, String occupiedMode) throws Exception {
        return objectMapper.writeValueAsString(new SeriesReservationRequest(
                subnetId, startIp, count, null, prefix, null, null, null, occupiedMode, null, null));
    }
}
