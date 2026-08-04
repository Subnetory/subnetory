package dev.subnetory.web;

import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.config.SecurityConfig;
import dev.subnetory.dto.SiteResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.NetworkContextService;
import dev.subnetory.service.SiteService;
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

@WebMvcTest(SiteWebController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class SiteCrudWebIT {

    @Autowired
    MockMvc mvc;

    @MockitoBean SiteService siteService;
    @MockitoBean NetworkContextService contextService;
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
    private SiteResponse sampleSite;

    @BeforeEach
    void setUp() {
        sampleSite = new SiteResponse(
                1L, "Paris Nord", "PAR01", 10L, "Production",
                OffsetDateTime.now(), OffsetDateTime.now());
        when(contextService.findAll(any(Pageable.class))).thenReturn(Page.empty());
    }

    // â”€â”€ AccÃ¨s anonyme â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getNewForm_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/sites/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void getEditForm_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/sites/1/edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void postCreate_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(post("/network/sites")
                        .with(csrf())
                        .param("name", "Test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // â”€â”€ CSRF â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/sites")
                        .param("name", "Test")
                        .param("code", "TST01")
                        .param("contextId", "10"))
                .andExpect(status().isForbidden());

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/sites/1/delete"))
                .andExpect(status().isForbidden());

        verify(siteService, never()).delete(any());
    }

    // â”€â”€ GET formulaire crÃ©ation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void getNewForm_roleNetwork_returns200() throws Exception {
        mvc.perform(get("/network/sites/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"))
                .andExpect(model().attributeExists("form", "allContexts"))
                .andExpect(model().attribute("pageTitle", "Nouveau site"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getNewForm_roleAdmin_returns200() throws Exception {
        mvc.perform(get("/network/sites/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void getNewForm_roleIpOnly_returns403() throws Exception {
        mvc.perform(get("/network/sites/new"))
                .andExpect(status().isForbidden());
    }

    // â”€â”€ POST crÃ©ation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_validData_redirectsToListWithFlash() throws Exception {
        when(siteService.create(any())).thenReturn(sampleSite);

        mvc.perform(post("/network/sites")
                        .with(csrf())
                        .param("name", "Paris Nord")
                        .param("code", "PAR01")
                        .param("contextId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/sites"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(siteService).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_blankName_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/sites")
                        .with(csrf())
                        .param("name", "")
                        .param("code", "PAR01")
                        .param("contextId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_blankCode_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/sites")
                        .with(csrf())
                        .param("name", "Paris Nord")
                        .param("code", "")
                        .param("contextId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"))
                .andExpect(model().attributeHasFieldErrors("form", "code"));

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_nullContextId_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/sites")
                        .with(csrf())
                        .param("name", "Paris Nord")
                        .param("code", "PAR01"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"))
                .andExpect(model().attributeHasFieldErrors("form", "contextId"));

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postCreate_conflictCode_reRendersFormWithBusinessError() throws Exception {
        when(siteService.create(any()))
                .thenThrow(new ConflictException("Site with code 'PAR01' already exists"));

        mvc.perform(post("/network/sites")
                        .with(csrf())
                        .param("name", "Paris Nord")
                        .param("code", "PAR01")
                        .param("contextId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"))
                .andExpect(model().attributeExists("formError", "allContexts"));
    }

    // â”€â”€ GET formulaire Ã©dition â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void getEditForm_existingId_returns200WithPrefilledForm() throws Exception {
        when(siteService.findById(1L)).thenReturn(sampleSite);

        mvc.perform(get("/network/sites/1/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"))
                .andExpect(model().attributeExists("form", "allContexts"))
                .andExpect(model().attribute("pageTitle", "Modifier le site"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void getEditForm_unknownId_returns404() throws Exception {
        when(siteService.findById(999L))
                .thenThrow(new ResourceNotFoundException("Site", 999L));

        mvc.perform(get("/network/sites/999/edit"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    // â”€â”€ POST Ã©dition â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_validData_redirectsToListWithFlash() throws Exception {
        when(siteService.update(eq(1L), any())).thenReturn(sampleSite);

        mvc.perform(post("/network/sites/1")
                        .with(csrf())
                        .param("name", "Paris Nord modifiÃ©")
                        .param("code", "PAR01")
                        .param("contextId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/sites"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_blankName_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/sites/1")
                        .with(csrf())
                        .param("name", "")
                        .param("code", "PAR01")
                        .param("contextId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));

        verify(siteService, never()).update(any(), any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_unknownId_returns404() throws Exception {
        when(siteService.update(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("Site", 999L));

        mvc.perform(post("/network/sites/999")
                        .with(csrf())
                        .param("name", "Test")
                        .param("code", "TST01")
                        .param("contextId", "10"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postUpdate_conflictCode_reRendersFormWithBusinessError() throws Exception {
        when(siteService.update(eq(1L), any()))
                .thenThrow(new ConflictException("Site with code 'PAR01' already exists"));

        mvc.perform(post("/network/sites/1")
                        .with(csrf())
                        .param("name", "Paris Nord")
                        .param("code", "PAR01")
                        .param("contextId", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/site-form"))
                .andExpect(model().attributeExists("formError"));
    }

    // â”€â”€ POST suppression â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_success_redirectsWithFlashSuccess() throws Exception {
        when(siteService.findById(1L)).thenReturn(sampleSite);
        doNothing().when(siteService).delete(1L);

        mvc.perform(post("/network/sites/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/sites"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(siteService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_unknownId_redirectsWithFlashError() throws Exception {
        doThrow(new ResourceNotFoundException("Site", 999L))
                .when(siteService).delete(999L);

        mvc.perform(post("/network/sites/999/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/sites"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_linkedResources_redirectsWithFkError() throws Exception {
        doThrow(new DataIntegrityViolationException("FK violation"))
                .when(siteService).delete(1L);

        mvc.perform(post("/network/sites/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/sites"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postDelete_roleNetwork_returns403() throws Exception {
        mvc.perform(post("/network/sites/1/delete").with(csrf()))
                .andExpect(status().isForbidden());

        verify(siteService, never()).delete(any());
    }
}
