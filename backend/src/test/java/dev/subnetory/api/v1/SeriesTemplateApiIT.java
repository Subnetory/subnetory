package dev.subnetory.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.dto.SeriesTemplateDuplicateRequest;
import dev.subnetory.dto.SeriesTemplateExpandRequest;
import dev.subnetory.dto.SeriesTemplateRequest;
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
 * Tests d'integration des templates de reservation en serie
 * ({@code /api/v1/series-templates}) : presets, application a un sous-reseau (apercu puis
 * creation), templates personnalises (CRUD, unicite, duplication), mode entrelace,
 * chevauchement entre groupes et audit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeriesTemplateApiIT {

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
    static Long customId;

    static final Long SEED_CONTEXT_ID = 1L; // contexte "Default" cree par V3
    static final String TEMPLATES_URL = "/api/v1/series-templates";

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
                                new SiteRequest("Site Templates Test", "TPL-API", SEED_CONTEXT_ID))))
                .andExpect(status().isCreated()).andReturn();
        Long siteId = objectMapper.readTree(sr.getResponse().getContentAsString()).get("id").asLong();

        MvcResult snr = mvc.perform(post("/api/v1/subnets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubnetRequest("172.19.0.0/24", "Subnet templates test",
                                        "172.19.0.1", SEED_CONTEXT_ID, siteId, null, null))))
                .andExpect(status().isCreated()).andReturn();
        subnetId = objectMapper.readTree(snr.getResponse().getContentAsString()).get("id").asLong();
    }

    // -------------------------------------------------------
    // Presets
    // -------------------------------------------------------

    @Test @Order(3)
    @DisplayName("GET /series-templates - liste les presets livres, en lecture seule")
    void list_returnsBuiltinPresets() throws Exception {
        mvc.perform(get(TEMPLATES_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$[0].builtin", is(true)))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[?(@.key == 'builtin:nutanix-hpe')]", hasSize(1)));
    }

    @Test @Order(4)
    @DisplayName("GET /series-templates/{key} - detail d'un preset, 404 si inconnu")
    void get_builtinDetailAndUnknown() throws Exception {
        mvc.perform(get(TEMPLATES_URL + "/builtin:nutanix-hpe")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.builtin", is(true)))
                .andExpect(jsonPath("$.definition.allocation", is("groups-sequential")))
                .andExpect(jsonPath("$.definition.groups[0].role", is("HOST")))
                .andExpect(jsonPath("$.definition.groups[1].role", is("CVM")))
                .andExpect(jsonPath("$.definition.groups[2].role", is("ILO")));

        mvc.perform(get(TEMPLATES_URL + "/builtin:nope")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(TEMPLATES_URL + "/custom:999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test @Order(5)
    @DisplayName("GET /series-templates - sans authentification -> 401")
    void list_withoutTokenReturns401() throws Exception {
        mvc.perform(get(TEMPLATES_URL)).andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------
    // Application d'un preset
    // -------------------------------------------------------

    @Test @Order(6)
    @DisplayName("POST /expand - applique un preset : hotes, CVM puis iLO a la suite, sans rien ecrire")
    void expand_buildsChainedGroupsWithoutWriting() throws Exception {
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("builtin:nutanix-hpe", 3, "NTNX01", "172.19.0.21")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.canCreate", is(true)))
                .andExpect(jsonPath("$.preview.requested", is(9)))
                .andExpect(jsonPath("$.preview.groups", hasSize(3)))
                .andExpect(jsonPath("$.preview.groups[0].label", is("HOST")))
                .andExpect(jsonPath("$.preview.groups[0].rows[0].ip", is("172.19.0.21")))
                .andExpect(jsonPath("$.preview.groups[0].rows[0].hostname", is("NTNX01-HOST-01")))
                .andExpect(jsonPath("$.preview.groups[1].rows[0].ip", is("172.19.0.24")))
                .andExpect(jsonPath("$.preview.groups[1].rows[0].hostname", is("NTNX01-CVM-01")))
                .andExpect(jsonPath("$.preview.groups[2].rows[2].ip", is("172.19.0.29")))
                .andExpect(jsonPath("$.preview.groups[2].rows[2].hostname", is("NTNX01-ILO-03")))
                .andExpect(jsonPath("$.request.groups", hasSize(3)));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test @Order(7)
    @DisplayName("La demande calculee par /expand se cree telle quelle sur /addresses/series/groups")
    void expandedRequest_canBeCreatedAsIs() throws Exception {
        MvcResult expanded = mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("builtin:nutanix-hpe", 3, "NTNX01", "172.19.0.21")))
                .andExpect(status().isOk()).andReturn();
        String request = objectMapper.readTree(expanded.getResponse().getContentAsString())
                .get("request").toString();

        mvc.perform(post("/api/v1/addresses/series/groups")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groups", hasSize(3)));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "NTNX01-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(9)));
    }

    @Test @Order(8)
    @DisplayName("POST /expand - adresses deja reservees : apercu OCCUPIED, canCreate=false")
    void expand_flagsOccupiedAddresses() throws Exception {
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("builtin:nutanix-hpe", 3, "NTNX02", "172.19.0.21")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.canCreate", is(false)))
                .andExpect(jsonPath("$.preview.groups[0].rows[0].status", is("OCCUPIED")));
    }

    @Test @Order(9)
    @DisplayName("POST /expand - parametres invalides -> 400, template inconnu -> 404")
    void expand_invalidRequests() throws Exception {
        // cluster obligatoire (variable du preset)
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("builtin:nutanix-hpe", 3, null, "172.19.0.60")))
                .andExpect(status().isBadRequest());
        // nombre de noeuds absent
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("builtin:nutanix-hpe", null, "NTNX03", "172.19.0.60")))
                .andExpect(status().isBadRequest());
        // IP de depart invalide
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("builtin:nutanix-hpe", 3, "NTNX03", "300.1.1.1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.startIp").exists());
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("builtin:nope", 3, "NTNX03", "172.19.0.60")))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------
    // Templates personnalises
    // -------------------------------------------------------

    @Test @Order(10)
    @DisplayName("POST /series-templates - cree un template personnalise entrelace")
    void create_customInterleavedTemplate() throws Exception {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(
                SeriesTemplateDefinition.INTERLEAVED, null, null, null, List.of(
                new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-{role}-{nn}", null, null),
                new SeriesTemplateDefinition.Group("CVM", null, "{cluster}-{role}-{nn}", null, null),
                new SeriesTemplateDefinition.Group("IPMI", null, "{cluster}-{role}-{nn}", null, null)));

        MvcResult r = mvc.perform(post(TEMPLATES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeriesTemplateRequest(
                                SEED_CONTEXT_ID, "Cluster entrelace", "Un noeud = 3 adresses", definition))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.builtin", is(false)))
                .andExpect(jsonPath("$.contextId", is(SEED_CONTEXT_ID.intValue())))
                .andExpect(jsonPath("$.definition.allocation", is("interleaved")))
                .andReturn();
        customId = objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
        assertThat(customId).isNotNull();
    }

    @Test @Order(11)
    @DisplayName("GET /series-templates?contextId - presets puis template du contexte")
    void list_includesCustomTemplate() throws Exception {
        mvc.perform(get(TEMPLATES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("contextId", String.valueOf(SEED_CONTEXT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'custom:" + customId + "')]", hasSize(1)));

        mvc.perform(get(TEMPLATES_URL + "/custom:" + customId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Cluster entrelace")));
    }

    @Test @Order(12)
    @DisplayName("POST /expand - mode entrelace : hote, CVM et IPMI d'un noeud se suivent")
    void expand_interleavedKeepsNodeAddressesTogether() throws Exception {
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("custom:" + customId, 2, "NTNX10", "172.19.0.101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.canCreate", is(true)))
                .andExpect(jsonPath("$.preview.groups[0].rows[0].ip", is("172.19.0.101")))
                .andExpect(jsonPath("$.preview.groups[0].rows[0].hostname", is("NTNX10-HOST-01")))
                .andExpect(jsonPath("$.preview.groups[1].rows[0].ip", is("172.19.0.102")))
                .andExpect(jsonPath("$.preview.groups[1].rows[0].hostname", is("NTNX10-CVM-01")))
                .andExpect(jsonPath("$.preview.groups[2].rows[0].ip", is("172.19.0.103")))
                .andExpect(jsonPath("$.preview.groups[0].rows[1].ip", is("172.19.0.104")))
                .andExpect(jsonPath("$.preview.groups[0].rows[1].hostname", is("NTNX10-HOST-02")))
                .andExpect(jsonPath("$.preview.groups[2].rows[1].ip", is("172.19.0.106")));
    }

    @Test @Order(13)
    @DisplayName("POST /series-templates - nom deja pris (casse ignoree) -> 409 ; format invalide -> 400")
    void create_duplicateNameAndInvalidDefinition() throws Exception {
        SeriesTemplateDefinition valid = new SeriesTemplateDefinition(null, null, null, null, List.of(
                new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-{nn}", null, null)));
        mvc.perform(post(TEMPLATES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeriesTemplateRequest(
                                SEED_CONTEXT_ID, "CLUSTER ENTRELACE", null, valid))))
                .andExpect(status().isConflict());

        SeriesTemplateDefinition noCounter = new SeriesTemplateDefinition(null, null, null, null, List.of(
                new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-HOST", null, null)));
        mvc.perform(post(TEMPLATES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeriesTemplateRequest(
                                SEED_CONTEXT_ID, "Sans compteur", null, noCounter))))
                .andExpect(status().isBadRequest());

        mvc.perform(post(TEMPLATES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contextId\":1,\"name\":\"\",\"definition\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.definition").exists());
    }

    @Test @Order(14)
    @DisplayName("POST /series-templates - contexte inexistant -> 404")
    void create_unknownContextReturns404() throws Exception {
        SeriesTemplateDefinition valid = new SeriesTemplateDefinition(null, null, null, null, List.of(
                new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-{nn}", null, null)));
        mvc.perform(post(TEMPLATES_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeriesTemplateRequest(
                                999999L, "Fantome", null, valid))))
                .andExpect(status().isNotFound());
    }

    @Test @Order(15)
    @DisplayName("POST /{key}/duplicate - copie un preset en template personnalise, nom unique")
    void duplicate_presetIntoContext() throws Exception {
        mvc.perform(post(TEMPLATES_URL + "/builtin:nutanix-dell/duplicate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SeriesTemplateDuplicateRequest(SEED_CONTEXT_ID, "Nutanix Dell maison"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.builtin", is(false)))
                .andExpect(jsonPath("$.name", is("Nutanix Dell maison")))
                .andExpect(jsonPath("$.definition.groups[2].role", is("IDRAC")));

        mvc.perform(post(TEMPLATES_URL + "/builtin:nutanix-dell/duplicate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SeriesTemplateDuplicateRequest(SEED_CONTEXT_ID, "nutanix dell maison"))))
                .andExpect(status().isConflict());

        mvc.perform(post(TEMPLATES_URL + "/builtin:nope/duplicate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SeriesTemplateDuplicateRequest(SEED_CONTEXT_ID, "Autre"))))
                .andExpect(status().isNotFound());
    }

    @Test @Order(16)
    @DisplayName("PUT /series-templates/{id} - modifie ; changement de contexte -> 409")
    void update_customTemplate() throws Exception {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(null, 2, null, null, List.of(
                new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-{role}-{nn}", null, null),
                new SeriesTemplateDefinition.Group("IPMI", null, "{cluster}-{role}-{nn}", null, null)));

        mvc.perform(put(TEMPLATES_URL + "/" + customId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeriesTemplateRequest(
                                SEED_CONTEXT_ID, "Cluster 2 groupes", "Modifie", definition))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Cluster 2 groupes")))
                .andExpect(jsonPath("$.definition.allocation", is("groups-sequential")))
                .andExpect(jsonPath("$.definition.groupGap", is(2)))
                .andExpect(jsonPath("$.definition.groups", hasSize(2)));

        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBody("custom:" + customId, 2, "SRV", "172.19.0.121")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.groups[0].rows[1].ip", is("172.19.0.122")))
                .andExpect(jsonPath("$.preview.groups[1].rows[0].ip", is("172.19.0.125")));

        mvc.perform(put(TEMPLATES_URL + "/" + customId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeriesTemplateRequest(
                                2L, "Cluster 2 groupes", null, definition))))
                .andExpect(status().isConflict());

        mvc.perform(put(TEMPLATES_URL + "/999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SeriesTemplateRequest(
                                SEED_CONTEXT_ID, "Inconnu", null, definition))))
                .andExpect(status().isNotFound());
    }

    @Test @Order(17)
    @DisplayName("DELETE /series-templates/{id} - supprime puis 404")
    void delete_customTemplate() throws Exception {
        mvc.perform(delete(TEMPLATES_URL + "/" + customId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        mvc.perform(get(TEMPLATES_URL + "/custom:" + customId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mvc.perform(delete(TEMPLATES_URL + "/" + customId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------
    // Chevauchement entre groupes
    // -------------------------------------------------------

    @Test @Order(18)
    @DisplayName("Groupes qui se chevauchent : DUPLICATE dans l'apercu, creation refusee (409), rien de cree")
    void overlappingGroupsAreFlaggedAndRejected() throws Exception {
        String body = objectMapper.writeValueAsString(new SeriesGroupsRequest(
                subnetId, "172.19.0.201", 0, null, null, null, List.of(
                new SeriesGroupsRequest.Group("A", 3, null, "OVL-A-", null, null, null, null),
                new SeriesGroupsRequest.Group("B", 2, null, "OVL-B-", null, null, null, "172.19.0.202"))));

        mvc.perform(post("/api/v1/addresses/series/groups/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canCreate", is(false)))
                .andExpect(jsonPath("$.groups[0].rows[0].status", is("AVAILABLE")))
                .andExpect(jsonPath("$.groups[1].rows[0].ip", is("172.19.0.202")))
                .andExpect(jsonPath("$.groups[1].rows[0].status", is("DUPLICATE")))
                .andExpect(jsonPath("$.groups[1].rows[1].status", is("DUPLICATE")));

        mvc.perform(post("/api/v1/addresses/series/groups")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.preview.canCreate", is(false)));

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("subnetId", String.valueOf(subnetId))
                        .param("hostnameContains", "OVL-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    // -------------------------------------------------------
    // Audit
    // -------------------------------------------------------

    @Test @Order(19)
    @DisplayName("Creation, modification, duplication et suppression sont journalisees")
    void templateChangesAreAudited() {
        assertThat(authAuditLogRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthAuditService.SERIES_TEMPLATE_CREATED)).hasSize(2)
                .anyMatch(log -> log.getMessage().contains("name=Cluster entrelace"))
                .anyMatch(log -> log.getMessage().contains("name=Nutanix Dell maison"));
        assertThat(authAuditLogRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthAuditService.SERIES_TEMPLATE_UPDATED)).hasSize(1)
                .anyMatch(log -> log.getMessage().contains("name=Cluster 2 groupes"));
        assertThat(authAuditLogRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthAuditService.SERIES_TEMPLATE_DELETED)).hasSize(1)
                .anyMatch(log -> log.getMessage().contains("id=" + customId));
    }

    // -------------------------------------------------------
    // Un sous-reseau par role (per-group)
    // -------------------------------------------------------

    @Test @Order(20)
    @DisplayName("POST /expand - preset per-group : depart et sous-reseau par role, chainage sinon")
    void expand_perGroupPresetWithRoleTargets() throws Exception {
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBodyWithRoles("builtin:nutanix-per-network", 3, "NTNX04",
                                new SeriesTemplateExpandRequest.RoleTarget("IPMI", subnetId, "172.19.0.150"),
                                new SeriesTemplateExpandRequest.RoleTarget("HOST", subnetId, "172.19.0.170"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.canCreate", is(true)))
                .andExpect(jsonPath("$.preview.groups", hasSize(3)))
                .andExpect(jsonPath("$.preview.groups[0].label", is("IPMI")))
                .andExpect(jsonPath("$.preview.groups[0].subnetId", is(subnetId.intValue())))
                .andExpect(jsonPath("$.preview.groups[0].rows[0].ip", is("172.19.0.150")))
                .andExpect(jsonPath("$.preview.groups[1].rows[0].ip", is("172.19.0.170")))
                .andExpect(jsonPath("$.preview.groups[2].rows[0].ip", is("172.19.0.173")))
                .andExpect(jsonPath("$.preview.groups[2].rows[0].hostname", is("NTNX04-CVM-01")));
    }

    @Test @Order(21)
    @DisplayName("POST /expand - cibles par role sur un template sequentiel -> 400")
    void expand_roleTargetsOnSequentialTemplateReturns400() throws Exception {
        mvc.perform(post(TEMPLATES_URL + "/expand")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expandBodyWithRoles("builtin:nutanix-hpe", 3, "NTNX05",
                                new SeriesTemplateExpandRequest.RoleTarget("HOST", subnetId, "172.19.0.180"))))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private String expandBody(String key, Integer nodes, String cluster, String startIp) throws Exception {
        return objectMapper.writeValueAsString(new SeriesTemplateExpandRequest(
                subnetId, key, nodes, cluster, null, startIp, null, null, null, null, null));
    }

    private String expandBodyWithRoles(String key, Integer nodes, String cluster,
                                       SeriesTemplateExpandRequest.RoleTarget... roles) throws Exception {
        return objectMapper.writeValueAsString(new SeriesTemplateExpandRequest(
                subnetId, key, nodes, cluster, null, null, null, null, null, null, null, List.of(roles)));
    }
}
