package dev.subnetory.web;

import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.config.SecurityConfig;
import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesGroupsResponse;
import dev.subnetory.dto.SeriesReservationResponse;
import dev.subnetory.dto.SeriesReservationResponse.Entry;
import dev.subnetory.dto.SeriesRowsRequest;
import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.dto.SeriesTemplateExpandRequest;
import dev.subnetory.dto.SeriesTemplateExpansionResponse;
import dev.subnetory.dto.SeriesTemplateResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.exception.SeriesConflictException;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.SeriesReservationService;
import dev.subnetory.service.SeriesTemplateService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.util.ImportFileValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Mode serie de la page de reservation multiple : generation du tableau,
 * controle des lignes et creation atomique (service de reservation simule).
 * Le rendu Thymeleaf complet de la page est exerce.
 */
@WebMvcTest(AddressWebController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, ImportFileValidator.class})
class AddressReserveSeriesWebIT {

    @Autowired
    MockMvc mvc;

    @MockitoBean AddressService addressService;
    @MockitoBean SubnetService subnetService;
    @MockitoBean IpAllocService ipAllocService;
    @MockitoBean SeriesReservationService seriesReservationService;
    @MockitoBean SeriesTemplateService seriesTemplateService;
    @MockitoBean AuthAuditService authAuditService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean SubnetoryUserDetailsService userDetailsService;
    @MockitoBean LoginRateLimiter loginRateLimiter;
    @MockitoBean ApiRateLimiter apiRateLimiter;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean RateLimitingAuthenticationFailureHandler failureHandler;
    @MockitoBean RateLimitingAuthenticationSuccessHandler successHandler;
    @MockitoBean RestoreMaintenanceGate restoreMaintenanceGate;

    @BeforeEach
    void setUp() {
        when(restoreMaintenanceGate.tryAdmitMutation()).thenReturn(true);
        when(subnetService.findAll(any(Pageable.class))).thenReturn(Page.empty());
    }

    // ── POST /reserve/series ─────────────────────────────────────────────

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void series_preview_rendersEditableRowsWithStatus() throws Exception {
        when(seriesReservationService.preview(any())).thenReturn(response(true,
                entry("10.10.10.21", "NTNX-HOST-01", "AVAILABLE"),
                entry("10.10.10.22", "NTNX-HOST-02", "AVAILABLE")));

        String html = mvc.perform(post("/network/addresses/reserve/series").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("seriesCount", "2")
                        .param("seriesPrefix", "NTNX-HOST-")
                        .param("seriesDescription", "Nutanix cluster"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve"))
                .andExpect(model().attributeDoesNotExist("formError"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("name=\"rows[0].address\"")
                .contains("value=\"10.10.10.21\"")
                .contains("name=\"rows[1].hostname\"")
                .contains("value=\"NTNX-HOST-02\"")
                .contains("value=\"Nutanix cluster\"")
                .contains("name=\"atomic\"")
                .contains("sn-badge--green")
                .contains("/network/addresses/reserve/check");

        ArgumentCaptor<dev.subnetory.dto.SeriesReservationRequest> captor =
                ArgumentCaptor.forClass(dev.subnetory.dto.SeriesReservationRequest.class);
        verify(seriesReservationService).preview(captor.capture());
        assertThat(captor.getValue().subnetId()).isEqualTo(30L);
        assertThat(captor.getValue().startIp()).isEqualTo("10.10.10.21");
        assertThat(captor.getValue().count()).isEqualTo(2);
        assertThat(captor.getValue().occupiedMode()).isEqualTo("block");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void series_skipOption_isSentAsSkipMode() throws Exception {
        when(seriesReservationService.preview(any())).thenReturn(new SeriesReservationResponse(
                "10.10.10.0/24", "skip", true, 2, 0,
                List.of(entry("10.10.10.21", "H-01", "AVAILABLE"), entry("10.10.10.23", "H-02", "AVAILABLE")),
                List.of(entry("10.10.10.22", null, "OCCUPIED"))));

        String html = mvc.perform(post("/network/addresses/reserve/series").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("seriesCount", "2")
                        .param("seriesSkipOccupied", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<dev.subnetory.dto.SeriesReservationRequest> captor =
                ArgumentCaptor.forClass(dev.subnetory.dto.SeriesReservationRequest.class);
        verify(seriesReservationService).preview(captor.capture());
        assertThat(captor.getValue().occupiedMode()).isEqualTo("skip");
        assertThat(html).contains("10.10.10.22");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void series_conflict_showsErrorAndStatusBadges() throws Exception {
        when(seriesReservationService.preview(any())).thenReturn(response(false,
                entry("10.10.10.21", "NTNX-HOST-01", "AVAILABLE"),
                entry("10.10.10.22", "NTNX-HOST-02", "OCCUPIED")));

        String html = mvc.perform(post("/network/addresses/reserve/series").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("seriesCount", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("sn-alert--error").contains("sn-badge--danger");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void series_invalidParameters_showsErrorWithoutRows() throws Exception {
        when(seriesReservationService.preview(any()))
                .thenThrow(new InvalidSeriesException("The count must be between 1 and 256."));

        mvc.perform(post("/network/addresses/reserve/series").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("seriesCount", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve"))
                .andExpect(model().attributeExists("formError"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void series_notANumber_showsErrorWithoutCallingService() throws Exception {
        mvc.perform(post("/network/addresses/reserve/series").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("seriesCount", "abc"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesReservationService, never()).preview(any());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void series_withoutSubnet_showsErrorWithoutCallingService() throws Exception {
        mvc.perform(post("/network/addresses/reserve/series").with(csrf())
                        .param("seriesStartIp", "10.10.10.21"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesReservationService, never()).preview(any());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "NETWORK")
    void series_withoutIpRole_isForbidden() throws Exception {
        mvc.perform(post("/network/addresses/reserve/series").with(csrf())
                        .param("subnetId", "30"))
                .andExpect(status().isForbidden());

        verify(seriesReservationService, never()).preview(any());
    }

    // ── POST /reserve/groups ─────────────────────────────────────────────

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void groups_preview_rendersGroupColumnAndIgnoresBlankLines() throws Exception {
        when(seriesReservationService.previewGroups(any())).thenReturn(groupsResponse(true,
                new SeriesGroupsResponse.Group("HOST", 2, 0, List.of(
                        entry("10.10.10.21", "H-01", "AVAILABLE"),
                        entry("10.10.10.22", "H-02", "AVAILABLE")), List.of()),
                new SeriesGroupsResponse.Group("CVM", 1, 0, List.of(
                        entry("10.10.10.24", "C-01", "AVAILABLE")), List.of())));

        String html = mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("seriesGap", "1")
                        .param("seriesDescription", "Nutanix cluster")
                        .param("groups[0].label", "HOST")
                        .param("groups[0].count", "2")
                        .param("groups[0].prefix", "H-")
                        .param("groups[1].label", "CVM")
                        .param("groups[1].count", "1")
                        .param("groups[1].prefix", "C-")
                        .param("groups[2].label", "")
                        .param("groups[2].count", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve"))
                .andExpect(model().attributeDoesNotExist("formError"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("name=\"rows[2].address\"")
                .contains("value=\"10.10.10.24\"")
                .contains("value=\"Nutanix cluster\"")
                .contains("name=\"rows[0].group\"")
                .contains("value=\"CVM\"")
                .contains("name=\"grouped\"")
                .contains("name=\"atomic\"");

        ArgumentCaptor<SeriesGroupsRequest> captor = ArgumentCaptor.forClass(SeriesGroupsRequest.class);
        verify(seriesReservationService).previewGroups(captor.capture());
        SeriesGroupsRequest sent = captor.getValue();
        assertThat(sent.subnetId()).isEqualTo(30L);
        assertThat(sent.startIp()).isEqualTo("10.10.10.21");
        assertThat(sent.gap()).isEqualTo(1);
        assertThat(sent.occupiedMode()).isEqualTo("block");
        assertThat(sent.groups()).extracting(SeriesGroupsRequest.Group::label)
                .containsExactly("HOST", "CVM");
        assertThat(sent.groups()).extracting(SeriesGroupsRequest.Group::count)
                .containsExactly(2, 1);
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void groups_skipOptionAndSkippedAddresses_areReported() throws Exception {
        when(seriesReservationService.previewGroups(any())).thenReturn(groupsResponse(true,
                new SeriesGroupsResponse.Group("HOST", 1, 0,
                        List.of(entry("10.10.10.23", "H-01", "AVAILABLE")),
                        List.of(entry("10.10.10.22", null, "OCCUPIED")))));

        String html = mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.22")
                        .param("seriesSkipOccupied", "true")
                        .param("groups[0].count", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("seriesSkipped", "10.10.10.22"))
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<SeriesGroupsRequest> captor = ArgumentCaptor.forClass(SeriesGroupsRequest.class);
        verify(seriesReservationService).previewGroups(captor.capture());
        assertThat(captor.getValue().occupiedMode()).isEqualTo("skip");
        assertThat(html).contains("10.10.10.22");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void groups_conflict_showsErrorAndStatusBadges() throws Exception {
        when(seriesReservationService.previewGroups(any())).thenReturn(groupsResponse(false,
                new SeriesGroupsResponse.Group("HOST", 1, 0,
                        List.of(entry("10.10.10.21", "H-01", "OCCUPIED")), List.of())));

        String html = mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("groups[0].count", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("sn-alert--error").contains("sn-badge--danger");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void groups_shortfall_showsErrorMessage() throws Exception {
        when(seriesReservationService.previewGroups(any())).thenReturn(groupsResponse(false,
                new SeriesGroupsResponse.Group("HOST", 3, 2,
                        List.of(entry("10.10.10.254", "H-01", "AVAILABLE")), List.of())));

        mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.254")
                        .param("seriesSkipOccupied", "true")
                        .param("groups[0].count", "3"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void groups_noGroupFilled_showsErrorWithoutCallingService() throws Exception {
        mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("groups[0].label", "HOST"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesReservationService, never()).previewGroups(any());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void groups_invalidParameters_showsError() throws Exception {
        when(seriesReservationService.previewGroups(any()))
                .thenThrow(new InvalidSeriesException("The start address must be a valid IPv4 address."));

        mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "oops")
                        .param("groups[0].count", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve"))
                .andExpect(model().attributeExists("formError"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void groups_notANumber_showsErrorWithoutCallingService() throws Exception {
        mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("subnetId", "30")
                        .param("seriesStartIp", "10.10.10.21")
                        .param("groups[0].count", "abc"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesReservationService, never()).previewGroups(any());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void groups_withoutSubnet_showsErrorWithoutCallingService() throws Exception {
        mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("groups[0].count", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesReservationService, never()).previewGroups(any());
    }

    @Test
    @WithMockUser(username = "viewer", roles = "NETWORK")
    void groups_withoutIpRole_isForbidden() throws Exception {
        mvc.perform(post("/network/addresses/reserve/groups").with(csrf())
                        .param("subnetId", "30"))
                .andExpect(status().isForbidden());

        verify(seriesReservationService, never()).previewGroups(any());
    }

    // ── POST /reserve/check ──────────────────────────────────────────────

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void check_allAvailable_showsSuccessMessage() throws Exception {
        when(seriesReservationService.previewRows(any())).thenReturn(response(true,
                entry("10.10.10.31", "NTNX-HOST-01", "AVAILABLE")));

        String html = mvc.perform(atomicPost("/network/addresses/reserve/check", "10.10.10.31"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formInfo"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("sn-alert--success");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void check_editedAddressOccupied_showsConflict() throws Exception {
        when(seriesReservationService.previewRows(any())).thenReturn(response(false,
                entry("10.10.10.31", "NTNX-HOST-01", "OCCUPIED")));

        mvc.perform(atomicPost("/network/addresses/reserve/check", "10.10.10.31"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"))
                .andExpect(model().attributeDoesNotExist("formInfo"));

        ArgumentCaptor<SeriesRowsRequest> captor = ArgumentCaptor.forClass(SeriesRowsRequest.class);
        verify(seriesReservationService).previewRows(captor.capture());
        assertThat(captor.getValue().rows()).singleElement()
                .satisfies(r -> assertThat(r.ip()).isEqualTo("10.10.10.31"));
    }

    // ── POST /reserve (soumission finale en mode serie) ──────────────────

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void submit_atomic_createsRowsAndRecordsAudit() throws Exception {
        when(seriesReservationService.createRows(any(), eq("operator"))).thenReturn(response(true,
                entry("10.10.10.31", "NTNX-HOST-01", "AVAILABLE"),
                entry("10.10.10.32", "NTNX-HOST-02", "AVAILABLE")));

        mvc.perform(post("/network/addresses/reserve").with(csrf())
                        .param("subnetId", "30")
                        .param("atomic", "true")
                        .param("rows[0].address", "10.10.10.31")
                        .param("rows[0].hostname", "NTNX-HOST-01")
                        .param("rows[0].included", "true")
                        .param("rows[1].address", "10.10.10.32")
                        .param("rows[1].hostname", "NTNX-HOST-02")
                        .param("rows[1].included", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve-result"));

        verify(seriesReservationService).createRows(any(), eq("operator"));
        verify(authAuditService).recordAddressSeriesReserved(
                "operator", 30L, "10.10.10.0/24", 2, "10.10.10.31", "10.10.10.32");
        verify(addressService, never()).bulkUpsert(any(), any());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void submit_atomic_conflict_redisplaysRowsWithStatusAndNoAudit() throws Exception {
        SeriesReservationResponse preview = response(false,
                entry("10.10.10.31", "NTNX-HOST-01", "OCCUPIED"));
        when(seriesReservationService.createRows(any(), anyString()))
                .thenThrow(new SeriesConflictException("cannot be created", preview));

        String html = mvc.perform(atomicPost("/network/addresses/reserve", "10.10.10.31"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve"))
                .andExpect(model().attributeExists("formError"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("sn-badge--danger").contains("value=\"10.10.10.31\"");
        verify(authAuditService, never()).recordAddressSeriesReserved(
                anyString(), anyLong(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void submit_atomic_concurrentReservation_showsErrorAndNoAudit() throws Exception {
        when(seriesReservationService.createRows(any(), anyString()))
                .thenThrow(new ConflictException("An address of the series was reserved concurrently"));

        mvc.perform(atomicPost("/network/addresses/reserve", "10.10.10.31"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve"))
                .andExpect(model().attributeExists("formError"));

        verify(authAuditService, never()).recordAddressSeriesReserved(
                anyString(), anyLong(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void submit_atomic_invalidRows_showsError() throws Exception {
        when(seriesReservationService.createRows(any(), anyString()))
                .thenThrow(new InvalidSeriesException("Hostname must be at most 100 characters."));

        mvc.perform(atomicPost("/network/addresses/reserve", "10.10.10.31"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void submit_atomic_noRowIncluded_showsErrorWithoutCallingService() throws Exception {
        mvc.perform(post("/network/addresses/reserve").with(csrf())
                        .param("subnetId", "30")
                        .param("atomic", "true")
                        .param("rows[0].address", "10.10.10.31")
                        .param("rows[0].included", "false"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesReservationService, never()).createRows(any(), anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder atomicPost(
            String url, String ip) {
        return post(url).with(csrf())
                .param("subnetId", "30")
                .param("atomic", "true")
                .param("rows[0].address", ip)
                .param("rows[0].hostname", "NTNX-HOST-01")
                .param("rows[0].included", "true");
    }

    private static Entry entry(String ip, String hostname, String status) {
        return new Entry(ip, hostname, status);
    }

    // ── POST /reserve/template ───────────────────────────────────────────

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void template_preview_rendersGroupRowsAndSendsParameters() throws Exception {
        SeriesGroupsResponse preview = groupsResponse(true,
                new SeriesGroupsResponse.Group("HOST", 1, 0,
                        List.of(entry("10.10.10.21", "NTNX01-HOST-01", "AVAILABLE")), List.of()),
                new SeriesGroupsResponse.Group("CVM", 1, 0,
                        List.of(entry("10.10.10.22", "NTNX01-CVM-01", "AVAILABLE")), List.of()));
        when(seriesTemplateService.expand(any())).thenReturn(
                new SeriesTemplateExpansionResponse(null, preview));

        String html = mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30")
                        .param("templateKey", "builtin:nutanix-hpe")
                        .param("templateCluster", "NTNX01")
                        .param("templateNodes", "1")
                        .param("templateStartIp", " 10.10.10.21 ")
                        .param("templateGap", "")
                        .param("templateStep", "2")
                        .param("templateOccupied", "skip")
                        .param("seriesDescription", "Cluster prod"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve"))
                .andExpect(model().attributeDoesNotExist("formError"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("name=\"rows[1].address\"")
                .contains("value=\"NTNX01-CVM-01\"")
                .contains("value=\"Cluster prod\"")
                .contains("name=\"rows[0].group\"")
                .contains("name=\"grouped\"")
                .contains("name=\"atomic\"");

        ArgumentCaptor<SeriesTemplateExpandRequest> captor =
                ArgumentCaptor.forClass(SeriesTemplateExpandRequest.class);
        verify(seriesTemplateService).expand(captor.capture());
        SeriesTemplateExpandRequest sent = captor.getValue();
        assertThat(sent.subnetId()).isEqualTo(30L);
        assertThat(sent.key()).isEqualTo("builtin:nutanix-hpe");
        assertThat(sent.cluster()).isEqualTo("NTNX01");
        assertThat(sent.nodes()).isEqualTo(1);
        assertThat(sent.startIp()).isEqualTo("10.10.10.21");
        assertThat(sent.gap()).isNull();
        assertThat(sent.step()).isEqualTo(2);
        assertThat(sent.occupiedMode()).isEqualTo("skip");
        assertThat(sent.description()).isEqualTo("Cluster prod");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void template_picker_listsBuiltinAndCustomTemplates() throws Exception {
        when(seriesTemplateService.list(any())).thenReturn(List.of(
                templateResponse("builtin:nutanix-hpe", "Nutanix - HPE", true),
                templateResponse("custom:7", "Mon cluster maison", false)));

        String html = mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("value=\"builtin:nutanix-hpe\"")
                .contains("Nutanix - HPE")
                .contains("value=\"custom:7\"")
                .contains("Mon cluster maison")
                .contains("/network/addresses/reserve/template");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void template_withoutKey_showsErrorWithoutCallingService() throws Exception {
        mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30")
                        .param("templateStartIp", "10.10.10.21"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesTemplateService, never()).expand(any());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void template_withoutSubnet_showsError() throws Exception {
        mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("templateKey", "builtin:nutanix-hpe"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesTemplateService, never()).expand(any());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void template_badNumber_showsError() throws Exception {
        mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30")
                        .param("templateKey", "builtin:nutanix-hpe")
                        .param("templateNodes", "abc"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        verify(seriesTemplateService, never()).expand(any());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void template_invalidParametersAndUnknownTemplate_showErrors() throws Exception {
        when(seriesTemplateService.expand(any()))
                .thenThrow(new InvalidSeriesException("The variable cluster is required by this template."))
                .thenThrow(new ResourceNotFoundException("SeriesTemplate", "custom:99"));

        mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30")
                        .param("templateKey", "builtin:nutanix-hpe")
                        .param("templateStartIp", "10.10.10.21"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));

        mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30")
                        .param("templateKey", "custom:99")
                        .param("templateStartIp", "10.10.10.21"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("formError"));
    }

    @Test
    @WithMockUser(username = "viewer", roles = "NETWORK")
    void template_withoutIpRole_isForbidden() throws Exception {
        mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30")
                        .param("templateKey", "builtin:nutanix-hpe"))
                .andExpect(status().isForbidden());

        verify(seriesTemplateService, never()).expand(any());
    }

    // ── Template « un sous-réseau par rôle » ─────────────────────────────

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void template_perGroup_sendsRoleTargetsAndShowsSubnetColumn() throws Exception {
        SeriesGroupsResponse preview = groupsResponse(true,
                new SeriesGroupsResponse.Group("IPMI", 1, 0,
                        List.of(entry("10.10.20.11", "NTNX01-IPMI-01", "AVAILABLE")), List.of(),
                        31L, "10.10.20.0/24"),
                new SeriesGroupsResponse.Group("HOST", 1, 0,
                        List.of(entry("10.10.10.21", "NTNX01-HOST-01", "AVAILABLE")), List.of(),
                        30L, "10.10.10.0/24"));
        when(seriesTemplateService.expand(any())).thenReturn(
                new SeriesTemplateExpansionResponse(null, preview));

        String html = mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30")
                        .param("templateKey", "builtin:nutanix-per-network")
                        .param("templateCluster", "NTNX01")
                        .param("templateNodes", "1")
                        .param("templateStartIp", "")
                        .param("templateRoles[0].role", "IPMI")
                        .param("templateRoles[0].subnetId", "31")
                        .param("templateRoles[0].startIp", " 10.10.20.11 ")
                        .param("templateRoles[1].role", "HOST")
                        .param("templateRoles[1].subnetId", "")
                        .param("templateRoles[1].startIp", "")
                        .param("templateRoles[2].role", "CVM")
                        .param("templateRoles[2].subnetId", "")
                        .param("templateRoles[2].startIp", ""))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("formError"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("name=\"multiSubnet\"")
                .contains("name=\"rows[0].subnetId\"")
                .contains("name=\"rows[1].subnetId\"")
                .contains("10.10.20.0/24")
                .contains("10.10.10.0/24");

        ArgumentCaptor<SeriesTemplateExpandRequest> captor =
                ArgumentCaptor.forClass(SeriesTemplateExpandRequest.class);
        verify(seriesTemplateService).expand(captor.capture());
        SeriesTemplateExpandRequest sent = captor.getValue();
        assertThat(sent.startIp()).isNull();
        // seul le role renseigne est transmis
        assertThat(sent.roles()).hasSize(1);
        assertThat(sent.roles().get(0).role()).isEqualTo("IPMI");
        assertThat(sent.roles().get(0).subnetId()).isEqualTo(31L);
        assertThat(sent.roles().get(0).startIp()).isEqualTo("10.10.20.11");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void template_picker_exposesAllocationAndRolesOfEachTemplate() throws Exception {
        when(seriesTemplateService.list(any())).thenReturn(List.of(
                templateResponse("builtin:nutanix-per-network", "Nutanix - un sous-reseau par role", true,
                        SeriesTemplateDefinition.PER_GROUP, "IPMI", "HOST", "CVM")));

        String html = mvc.perform(post("/network/addresses/reserve/template").with(csrf())
                        .param("subnetId", "30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("data-allocation=\"per-group\"")
                .contains("data-roles=\"IPMI,HOST,CVM\"")
                .contains("id=\"templateRolesBox\"")
                .contains("name=\"templateRoles[0].subnetId\"")
                .contains("name=\"templateRoles[9].startIp\"");
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void submit_atomic_multiSubnet_sendsRowSubnetsAndAuditsEachSubnet() throws Exception {
        when(seriesReservationService.createRows(any(), eq("operator"))).thenReturn(response(true,
                entry("10.10.20.11", "NTNX01-IPMI-01", "AVAILABLE"),
                entry("10.10.10.21", "NTNX01-HOST-01", "AVAILABLE")));

        mvc.perform(post("/network/addresses/reserve").with(csrf())
                        .param("subnetId", "30")
                        .param("atomic", "true")
                        .param("multiSubnet", "true")
                        .param("rows[0].address", "10.10.20.11")
                        .param("rows[0].hostname", "NTNX01-IPMI-01")
                        .param("rows[0].subnetId", "31")
                        .param("rows[0].subnetLabel", "10.10.20.0/24")
                        .param("rows[0].included", "true")
                        .param("rows[1].address", "10.10.10.21")
                        .param("rows[1].hostname", "NTNX01-HOST-01")
                        .param("rows[1].subnetId", "30")
                        .param("rows[1].subnetLabel", "10.10.10.0/24")
                        .param("rows[1].included", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-reserve-result"));

        ArgumentCaptor<SeriesRowsRequest> captor = ArgumentCaptor.forClass(SeriesRowsRequest.class);
        verify(seriesReservationService).createRows(captor.capture(), eq("operator"));
        assertThat(captor.getValue().rows()).extracting(SeriesRowsRequest.Row::subnetId)
                .containsExactly(31L, 30L);
        verify(authAuditService).recordAddressSeriesReserved(
                "operator", 31L, "10.10.20.0/24", 1, "10.10.20.11", "10.10.20.11");
        verify(authAuditService).recordAddressSeriesReserved(
                "operator", 30L, "10.10.10.0/24", 1, "10.10.10.21", "10.10.10.21");
    }

    private static SeriesTemplateResponse templateResponse(String key, String name, boolean builtin,
                                                           String allocation, String... roles) {
        List<SeriesTemplateDefinition.Group> groups = java.util.Arrays.stream(roles)
                .map(role -> new SeriesTemplateDefinition.Group(role, null, "{cluster}-{role}-{nn}", null, null))
                .toList();
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(allocation, null, null, null, groups);
        return new SeriesTemplateResponse(key, builtin, builtin ? null : 7L, builtin ? null : 1L,
                name, null, definition, null);
    }

    private static SeriesTemplateResponse templateResponse(String key, String name, boolean builtin) {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(null, null, null, null, List.of(
                new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-{role}-{nn}", null, null)));
        return new SeriesTemplateResponse(key, builtin, builtin ? null : 7L, builtin ? null : 1L,
                name, null, definition, null);
    }

    private static SeriesGroupsResponse groupsResponse(boolean canCreate, SeriesGroupsResponse.Group... groups) {
        return new SeriesGroupsResponse("10.10.10.0/24", "block", canCreate,
                java.util.Arrays.stream(groups).mapToInt(SeriesGroupsResponse.Group::requested).sum(),
                List.of(groups));
    }

    private static SeriesReservationResponse response(boolean canCreate, Entry... rows) {
        return new SeriesReservationResponse(
                "10.10.10.0/24", "block", canCreate, rows.length, 0, List.of(rows), List.of());
    }
}
