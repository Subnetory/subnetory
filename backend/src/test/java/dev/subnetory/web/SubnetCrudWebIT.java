package dev.subnetory.web;

import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.config.SecurityConfig;
import dev.subnetory.dto.AvailableIpResponse;
import dev.subnetory.dto.SubnetResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.scan.ScanService;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.NetworkContextService;
import dev.subnetory.service.SiteService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.service.VlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SubnetWebController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class SubnetCrudWebIT {

    @Autowired
    MockMvc mvc;

    @MockitoBean SubnetService subnetService;
    @MockitoBean IpAllocService ipAllocService;
    @MockitoBean ScanService scanService; // present dans le controller, doit etre mocke
    @MockitoBean NetworkContextService contextService;
    @MockitoBean SiteService siteService;
    @MockitoBean VlanService vlanService;
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
    // Correctif securite MOYENNE (audit 04/08/2026) : RestoreMaintenanceFilter,
    // cable dans SecurityConfig#webFilterChain, a besoin de ce bean.
    @MockitoBean RestoreMaintenanceGate restoreMaintenanceGate;
    private SubnetResponse sampleSubnet;

    @BeforeEach
    void setUp() {
        // Correctif regression (04/08/2026, troisieme audit externe, M-01) :
        // voir AddressCrudWebIT#setUp pour le detail de ce stub — sans lui,
        // RestoreMaintenanceFilter renvoie 503 sur toute mutation ici.
        when(restoreMaintenanceGate.tryAdmitMutation()).thenReturn(true);
        sampleSubnet = new SubnetResponse(
                1L, "10.0.0.0/24", "Test", null,
                10L, "Production", 20L, "PAR01",
                null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());

        // Stubs necessaires pour que list() et prepareFormModel() ne lancent pas de NPE
        when(subnetService.findAll(any(Pageable.class))).thenReturn(Page.empty());
        when(subnetService.findBySite(any(), any())).thenReturn(Page.empty());
        when(subnetService.findByContext(any(), any())).thenReturn(Page.empty());
        when(contextService.findAll(any(Pageable.class))).thenReturn(Page.empty());
        when(siteService.findAll(any(Pageable.class))).thenReturn(Page.empty());
        when(vlanService.findAll(any(Pageable.class))).thenReturn(Page.empty());
    }

    // Acces anonyme

    @Test
    void getNewForm_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/subnets/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void getEditForm_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/subnets/1/edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void postCreate_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(post("/network/subnets")
                        .with(csrf())
                        .param("network", "10.0.0.0/24"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }


    // IP disponible - endpoint Web session

    @Test
    void getAvailableIps_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/subnets/1/available-ips"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser
    void getAvailableIps_authenticated_returnsJson() throws Exception {
        when(ipAllocService.findAvailableIps(1L, 5))
                .thenReturn(new AvailableIpResponse(
                        "10.0.0.0/24", 5, 2,
                        List.of("10.0.0.1", "10.0.0.2")));

        mvc.perform(get("/network/subnets/1/available-ips"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.network").value("10.0.0.0/24"))
                .andExpect(jsonPath("$.requested").value(5))
                .andExpect(jsonPath("$.found").value(2))
                .andExpect(jsonPath("$.availableIps[0]").value("10.0.0.1"));

        verify(ipAllocService).findAvailableIps(1L, 5);
    }

    @Test
    @WithMockUser
    void getAvailableIps_authenticatedWithCustomCount_passesCount() throws Exception {
        when(ipAllocService.findAvailableIps(1L, 3))
                .thenReturn(new AvailableIpResponse(
                        "10.0.0.0/24", 3, 3,
                        List.of("10.0.0.1", "10.0.0.2", "10.0.0.3")));

        mvc.perform(get("/network/subnets/1/available-ips")
                        .param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.availableIps.length()").value(3));

        verify(ipAllocService).findAvailableIps(1L, 3);
    }

    @Test
    @WithMockUser
    void getAvailableIps_subnetFull_returnsEmptyList() throws Exception {
        when(ipAllocService.findAvailableIps(1L, 5))
                .thenReturn(new AvailableIpResponse("10.0.0.0/30", 5, 0, List.of()));

        mvc.perform(get("/network/subnets/1/available-ips"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(0))
                .andExpect(jsonPath("$.availableIps.length()").value(0));
    }

    @Test
    @WithMockUser
    void getAvailableIps_unknownSubnet_returns404() throws Exception {
        when(ipAllocService.findAvailableIps(999L, 5))
                .thenThrow(new ResourceNotFoundException("Subnet", 999L));

        mvc.perform(get("/network/subnets/999/available-ips"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"));
    }
    // CSRF

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/subnets")
                        .param("network", "10.0.0.0/24")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().isForbidden());

        verify(subnetService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/subnets/1/delete"))
                .andExpect(status().isForbidden());

        verify(subnetService, never()).delete(any());
    }

    // GET formulaire creation

    @Test
    @WithMockUser(roles = "NETWORK")
    void getNewForm_roleNetwork_returns200() throws Exception {
        mvc.perform(get("/network/subnets/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeExists("form", "allContexts", "allSites", "allVlans", "allSubnets"))
                .andExpect(model().attribute("pageTitle", "Nouveau sous-réseau"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getNewForm_roleAdmin_returns200() throws Exception {
        mvc.perform(get("/network/subnets/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void getNewForm_roleIpOnly_returns403() throws Exception {
        mvc.perform(get("/network/subnets/new"))
                .andExpect(status().isForbidden());
    }

    // POST creation

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_validData_redirectsToListWithFlash() throws Exception {
        when(subnetService.create(any())).thenReturn(sampleSubnet);

        mvc.perform(post("/network/subnets")
                        .with(csrf())
                        .param("network", "10.0.0.0/24")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/subnets"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(subnetService).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_blankNetwork_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/subnets")
                        .with(csrf())
                        .param("network", "")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeHasFieldErrors("form", "network"));

        verify(subnetService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_invalidCidr_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/subnets")
                        .with(csrf())
                        .param("network", "not-a-cidr")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeHasFieldErrors("form", "network"));

        verify(subnetService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_nullContextId_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/subnets")
                        .with(csrf())
                        .param("network", "10.0.0.0/24")
                        .param("siteId", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeHasFieldErrors("form", "contextId"));

        verify(subnetService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_nullSiteId_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/subnets")
                        .with(csrf())
                        .param("network", "10.0.0.0/24")
                        .param("contextId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeHasFieldErrors("form", "siteId"));

        verify(subnetService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_conflict_reRendersFormWithBusinessError() throws Exception {
        when(subnetService.create(any()))
                .thenThrow(new ConflictException("Subnet 10.0.0.0/24 already exists on site 20"));

        mvc.perform(post("/network/subnets")
                        .with(csrf())
                        .param("network", "10.0.0.0/24")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeExists("formError", "allContexts", "allSites"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_withOptionalFields_redirectsToList() throws Exception {
        when(subnetService.create(any())).thenReturn(sampleSubnet);

        mvc.perform(post("/network/subnets")
                        .with(csrf())
                        .param("network", "10.0.0.0/24")
                        .param("description", "Réseau test")
                        .param("gateway", "10.0.0.1")
                        .param("contextId", "10")
                        .param("siteId", "20")
                        .param("vlanId", "5")
                        .param("parentId", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/subnets"));
    }

    // GET formulaire edition

    @Test
    @WithMockUser(roles = "NETWORK")
    void getEditForm_existingId_returns200WithPrefilledForm() throws Exception {
        when(subnetService.findById(1L)).thenReturn(sampleSubnet);

        mvc.perform(get("/network/subnets/1/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeExists("form", "allContexts", "allSites", "allVlans", "allSubnets"))
                .andExpect(model().attribute("pageTitle", "Modifier le sous-réseau"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void getEditForm_unknownId_returns404() throws Exception {
        when(subnetService.findById(999L))
                .thenThrow(new ResourceNotFoundException("Subnet", 999L));

        mvc.perform(get("/network/subnets/999/edit"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    // POST edition

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_validData_redirectsToListWithFlash() throws Exception {
        when(subnetService.update(eq(1L), any())).thenReturn(sampleSubnet);

        mvc.perform(post("/network/subnets/1")
                        .with(csrf())
                        .param("network", "10.0.0.0/24")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/subnets"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_blankNetwork_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/subnets/1")
                        .with(csrf())
                        .param("network", "")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeHasFieldErrors("form", "network"));

        verify(subnetService, never()).update(any(), any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_unknownId_returns404() throws Exception {
        when(subnetService.update(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("Subnet", 999L));

        mvc.perform(post("/network/subnets/999")
                        .with(csrf())
                        .param("network", "10.0.0.0/24")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_conflict_reRendersFormWithBusinessError() throws Exception {
        when(subnetService.update(eq(1L), any()))
                .thenThrow(new ConflictException("Subnet already exists on site"));

        mvc.perform(post("/network/subnets/1")
                        .with(csrf())
                        .param("network", "10.0.0.0/24")
                        .param("contextId", "10")
                        .param("siteId", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/subnet-form"))
                .andExpect(model().attributeExists("formError"));
    }

    // POST suppression

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_success_redirectsWithFlashSuccess() throws Exception {
        when(subnetService.findById(1L)).thenReturn(sampleSubnet);
        doNothing().when(subnetService).delete(1L);

        mvc.perform(post("/network/subnets/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/subnets"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(subnetService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_unknownId_redirectsWithFlashError() throws Exception {
        doThrow(new ResourceNotFoundException("Subnet", 999L))
                .when(subnetService).delete(999L);

        mvc.perform(post("/network/subnets/999/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/subnets"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_linkedAddresses_redirectsWithFkError() throws Exception {
        doThrow(new DataIntegrityViolationException("FK violation"))
                .when(subnetService).delete(1L);

        mvc.perform(post("/network/subnets/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/subnets"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postDelete_roleNetwork_returns403() throws Exception {
        mvc.perform(post("/network/subnets/1/delete").with(csrf()))
                .andExpect(status().isForbidden());

        verify(subnetService, never()).delete(any());
    }
}
