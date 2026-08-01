package dev.subnetory.web;

import dev.subnetory.config.SecurityConfig;
import dev.subnetory.dto.VlanResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.SiteService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(VlanWebController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class VlanCrudWebIT {

    @Autowired
    MockMvc mvc;

    @MockitoBean VlanService vlanService;
    @MockitoBean SiteService siteService;
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
    private VlanResponse sampleVlan;

    @BeforeEach
    void setUp() {
        sampleVlan = new VlanResponse(
                1L, "VLAN-PROD", 100, 10L, "PAR01",
                OffsetDateTime.now(), OffsetDateTime.now());
        when(siteService.findAll(any(Pageable.class))).thenReturn(Page.empty());
    }

    // â”€â”€ AccÃ¨s anonyme â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getNewForm_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/vlans/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void getEditForm_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/vlans/1/edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void postCreate_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(post("/network/vlans")
                        .with(csrf())
                        .param("vid", "100"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // â”€â”€ CSRF â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/vlans")
                        .param("vid", "100")
                        .param("siteId", "10"))
                .andExpect(status().isForbidden());

        verify(vlanService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/vlans/1/delete"))
                .andExpect(status().isForbidden());

        verify(vlanService, never()).delete(any());
    }

    // â”€â”€ GET formulaire crÃ©ation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void getNewForm_roleNetwork_returns200() throws Exception {
        mvc.perform(get("/network/vlans/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeExists("form", "allSites"))
                .andExpect(model().attribute("pageTitle", "Nouveau VLAN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getNewForm_roleAdmin_returns200() throws Exception {
        mvc.perform(get("/network/vlans/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void getNewForm_roleIpOnly_returns403() throws Exception {
        mvc.perform(get("/network/vlans/new"))
                .andExpect(status().isForbidden());
    }

    // â”€â”€ POST crÃ©ation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK", username = "testnetwork")
    void postCreate_validData_redirectsToListWithFlash() throws Exception {
        when(vlanService.create(any())).thenReturn(sampleVlan);

        mvc.perform(post("/network/vlans")
                        .with(csrf())
                        .param("vid", "100")
                        .param("name", "VLAN-PROD")
                        .param("siteId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/vlans"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(vlanService).create(any());
        verify(authAuditService).recordVlanCreated(
                eq("testnetwork"), eq(sampleVlan.id()),
                eq("VLAN " + sampleVlan.vid() + " (" + sampleVlan.name() + ")"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_withoutName_succeeds() throws Exception {
        when(vlanService.create(any())).thenReturn(sampleVlan);

        mvc.perform(post("/network/vlans")
                        .with(csrf())
                        .param("vid", "200")
                        .param("siteId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/vlans"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_nullVid_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/vlans")
                        .with(csrf())
                        .param("name", "VLAN-TEST")
                        .param("siteId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeHasFieldErrors("form", "vid"));

        verify(vlanService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_vidTooHigh_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/vlans")
                        .with(csrf())
                        .param("vid", "4095")
                        .param("siteId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeHasFieldErrors("form", "vid"));

        verify(vlanService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_vidNegative_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/vlans")
                        .with(csrf())
                        .param("vid", "-1")
                        .param("siteId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeHasFieldErrors("form", "vid"));

        verify(vlanService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_nullSiteId_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/vlans")
                        .with(csrf())
                        .param("vid", "100"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeHasFieldErrors("form", "siteId"));

        verify(vlanService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_conflictVidSite_reRendersFormWithBusinessError() throws Exception {
        when(vlanService.create(any()))
                .thenThrow(new ConflictException("VLAN 100 already exists on site 10"));

        mvc.perform(post("/network/vlans")
                        .with(csrf())
                        .param("vid", "100")
                        .param("siteId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeExists("formError", "allSites"));
    }

    // â”€â”€ GET formulaire Ã©dition â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void getEditForm_existingId_returns200WithPrefilledForm() throws Exception {
        when(vlanService.findById(1L)).thenReturn(sampleVlan);

        mvc.perform(get("/network/vlans/1/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeExists("form", "allSites"))
                .andExpect(model().attribute("pageTitle", "Modifier le VLAN"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void getEditForm_unknownId_returns404() throws Exception {
        when(vlanService.findById(999L))
                .thenThrow(new ResourceNotFoundException("Vlan", 999L));

        mvc.perform(get("/network/vlans/999/edit"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    // â”€â”€ POST Ã©dition â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_validData_redirectsToListWithFlash() throws Exception {
        when(vlanService.update(eq(1L), any())).thenReturn(sampleVlan);

        mvc.perform(post("/network/vlans/1")
                        .with(csrf())
                        .param("vid", "100")
                        .param("name", "VLAN-PROD")
                        .param("siteId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/vlans"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_nullVid_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/vlans/1")
                        .with(csrf())
                        .param("siteId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeHasFieldErrors("form", "vid"));

        verify(vlanService, never()).update(any(), any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_unknownId_returns404() throws Exception {
        when(vlanService.update(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("Vlan", 999L));

        mvc.perform(post("/network/vlans/999")
                        .with(csrf())
                        .param("vid", "100")
                        .param("siteId", "10"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_conflictVidSite_reRendersFormWithBusinessError() throws Exception {
        when(vlanService.update(eq(1L), any()))
                .thenThrow(new ConflictException("VLAN 100 already exists on site 10"));

        mvc.perform(post("/network/vlans/1")
                        .with(csrf())
                        .param("vid", "100")
                        .param("siteId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/vlan-form"))
                .andExpect(model().attributeExists("formError"));
    }

    // â”€â”€ POST suppression â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "ADMIN", username = "testadmin")
    void postDelete_success_redirectsWithFlashSuccess() throws Exception {
        when(vlanService.findById(1L)).thenReturn(sampleVlan);
        doNothing().when(vlanService).delete(1L);

        mvc.perform(post("/network/vlans/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/vlans"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(vlanService).delete(1L);
        verify(authAuditService).recordVlanDeleted(
                eq("testadmin"), eq(1L),
                eq("VLAN " + sampleVlan.vid() + " (" + sampleVlan.name() + ")"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_unknownId_redirectsWithFlashError() throws Exception {
        doThrow(new ResourceNotFoundException("Vlan", 999L))
                .when(vlanService).delete(999L);

        mvc.perform(post("/network/vlans/999/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/vlans"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_linkedSubnets_redirectsWithFkError() throws Exception {
        doThrow(new DataIntegrityViolationException("FK violation"))
                .when(vlanService).delete(1L);

        mvc.perform(post("/network/vlans/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/vlans"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postDelete_roleNetwork_returns403() throws Exception {
        mvc.perform(post("/network/vlans/1/delete").with(csrf()))
                .andExpect(status().isForbidden());

        verify(vlanService, never()).delete(any());
    }
}
