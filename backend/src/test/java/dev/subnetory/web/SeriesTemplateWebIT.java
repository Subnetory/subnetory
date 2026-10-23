package dev.subnetory.web;

import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.config.SecurityConfig;
import dev.subnetory.dto.NetworkContextResponse;
import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.dto.SeriesTemplateRequest;
import dev.subnetory.dto.SeriesTemplateResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.NetworkContextService;
import dev.subnetory.service.SeriesTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

/** Page de gestion des templates de reservation en serie (services simules, rendu Thymeleaf complet). */
@WebMvcTest(SeriesTemplateWebController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class SeriesTemplateWebIT {

    @Autowired
    MockMvc mvc;

    @MockitoBean SeriesTemplateService templateService;
    @MockitoBean NetworkContextService contextService;
    @MockitoBean AuthAuditService authAuditService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean SubnetoryUserDetailsService userDetailsService;
    @MockitoBean LoginRateLimiter loginRateLimiter;
    @MockitoBean ApiRateLimiter apiRateLimiter;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean RateLimitingAuthenticationFailureHandler failureHandler;
    @MockitoBean RateLimitingAuthenticationSuccessHandler successHandler;
    @MockitoBean RestoreMaintenanceGate restoreMaintenanceGate;

    SeriesTemplateResponse builtin;
    SeriesTemplateResponse custom;

    @BeforeEach
    void setUp() {
        when(restoreMaintenanceGate.tryAdmitMutation()).thenReturn(true);
        NetworkContextResponse context = new NetworkContextResponse(
                1L, "Default", null, OffsetDateTime.now(), OffsetDateTime.now());
        Page<NetworkContextResponse> contexts = new PageImpl<>(List.of(context));
        when(contextService.findAll(any(Pageable.class))).thenReturn(contexts);

        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(null, null, null, null, List.of(
                new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-{role}-{nn}", null, null),
                new SeriesTemplateDefinition.Group("CVM", "Controller VM", "{cluster}-{role}-{nn}", 3, 5L)));
        builtin = new SeriesTemplateResponse("builtin:nutanix-hpe", true, null, null,
                "Nutanix - HPE", "Preset", definition, null);
        custom = new SeriesTemplateResponse("custom:7", false, 7L, 1L,
                "Mon cluster", null, definition, OffsetDateTime.now());
        when(templateService.list(any())).thenReturn(List.of(builtin, custom));
    }

    // ── Liste ────────────────────────────────────────────────────────────

    @Test
    void list_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/series-templates"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void list_roleIp_showsPresetsCustomTemplatesAndActions() throws Exception {
        String html = mvc.perform(get("/network/series-templates"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/series-templates"))
                .andExpect(model().attribute("canManage", true))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("Nutanix - HPE")
                .contains("Mon cluster")
                .contains("/network/series-templates/new?from=builtin")
                .contains("/network/series-templates/7/edit")
                .contains("/network/series-templates/7/delete");
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void list_readOnlyRole_hidesActions() throws Exception {
        String html = mvc.perform(get("/network/series-templates"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canManage", false))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("Nutanix - HPE")
                .doesNotContain("/network/series-templates/7/edit")
                .doesNotContain("/network/series-templates/new");
    }

    // ── Formulaire de création ───────────────────────────────────────────

    @Test
    @WithMockUser(roles = "NETWORK")
    void newForm_withoutIpRole_isForbidden() throws Exception {
        mvc.perform(get("/network/series-templates/new"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "IP")
    void newForm_empty_rendersTenGroupLines() throws Exception {
        String html = mvc.perform(get("/network/series-templates/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/series-template-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("name=\"groups[0].role\"")
                .contains("name=\"groups[9].format\"")
                .contains("name=\"allocation\"")
                .contains("value=\"per-group\"");
    }

    @Test
    @WithMockUser(roles = "IP")
    void newForm_fromPreset_prefillsAFreshCopy() throws Exception {
        when(templateService.get("builtin:nutanix-hpe")).thenReturn(builtin);

        String html = mvc.perform(get("/network/series-templates/new").param("from", "builtin:nutanix-hpe"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("value=\"HOST\"")
                .contains("value=\"CVM\"")
                .contains("value=\"{cluster}-{role}-{nn}\"")
                .contains("value=\"Controller VM\"");
    }

    @Test
    @WithMockUser(roles = "IP")
    void newForm_fromUnknownTemplate_returns404() throws Exception {
        when(templateService.get("builtin:nope")).thenThrow(new ResourceNotFoundException("SeriesTemplate", "builtin:nope"));

        mvc.perform(get("/network/series-templates/new").param("from", "builtin:nope"))
                .andExpect(status().isNotFound());
    }

    // ── Création ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void create_valid_savesAuditsAndRedirects() throws Exception {
        when(templateService.create(any())).thenReturn(custom);

        mvc.perform(post("/network/series-templates").with(csrf())
                        .param("contextId", "1")
                        .param("name", "Mon cluster")
                        .param("description", "Test")
                        .param("allocation", "interleaved")
                        .param("groupGap", "2")
                        .param("ipStep", "1")
                        .param("onOccupied", "block")
                        .param("groups[0].role", " HOST ")
                        .param("groups[0].format", "{cluster}-{role}-{nn}")
                        .param("groups[0].startCounter", "1")
                        .param("groups[1].role", "")
                        .param("groups[1].format", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/series-templates"))
                .andExpect(flash().attributeExists("flashSuccess"));

        ArgumentCaptor<SeriesTemplateRequest> captor = ArgumentCaptor.forClass(SeriesTemplateRequest.class);
        verify(templateService).create(captor.capture());
        SeriesTemplateRequest sent = captor.getValue();
        assertThat(sent.contextId()).isEqualTo(1L);
        assertThat(sent.name()).isEqualTo("Mon cluster");
        assertThat(sent.definition().allocation()).isEqualTo("interleaved");
        assertThat(sent.definition().groupGap()).isEqualTo(2);
        assertThat(sent.definition().groups()).hasSize(1);
        assertThat(sent.definition().groups().get(0).role()).isEqualTo("HOST");
        verify(authAuditService).recordSeriesTemplateCreated("operator", 7L, 1L, "Mon cluster");
    }

    @Test
    @WithMockUser(roles = "IP")
    void create_missingNameAndContext_rendersFormErrors() throws Exception {
        mvc.perform(post("/network/series-templates").with(csrf())
                        .param("name", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("network/series-template-form"))
                .andExpect(model().attributeHasFieldErrors("form", "name", "contextId"));

        verify(templateService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "IP")
    void create_serviceRejections_showFormError() throws Exception {
        when(templateService.create(any()))
                .thenThrow(new InvalidSeriesException("A name format must contain exactly one counter"))
                .thenThrow(new ConflictException("exists"))
                .thenThrow(new ResourceNotFoundException("NetworkContext", 1L));

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/network/series-templates").with(csrf())
                            .param("contextId", "1")
                            .param("name", "Mon cluster")
                            .param("groups[0].role", "HOST")
                            .param("groups[0].format", "{cluster}-HOST"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("network/series-template-form"))
                    .andExpect(model().attributeExists("formError"))
                    .andExpect(model().attribute("currentRequestPath", "/network/series-templates/new"));
        }
        verify(authAuditService, never()).recordSeriesTemplateCreated(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void create_withoutIpRole_isForbidden() throws Exception {
        mvc.perform(post("/network/series-templates").with(csrf())
                        .param("contextId", "1").param("name", "X"))
                .andExpect(status().isForbidden());

        verify(templateService, never()).create(any());
    }

    // ── Modification ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "IP")
    void editForm_showsReadOnlyContextAndGroups() throws Exception {
        when(templateService.get("custom:7")).thenReturn(custom);

        String html = mvc.perform(get("/network/series-templates/7/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/series-template-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("value=\"Mon cluster\"")
                .contains("value=\"HOST\"")
                .contains("action=\"/network/series-templates/7\"")
                .contains("Default");
    }

    @Test
    @WithMockUser(roles = "IP")
    void editForm_unknownTemplate_returns404() throws Exception {
        when(templateService.get("custom:99")).thenThrow(new ResourceNotFoundException("SeriesTemplate", 99L));

        mvc.perform(get("/network/series-templates/99/edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void update_valid_savesAuditsAndRedirects() throws Exception {
        when(templateService.update(eq(7L), any())).thenReturn(custom);

        mvc.perform(post("/network/series-templates/7").with(csrf())
                        .param("contextId", "1")
                        .param("name", "Mon cluster")
                        .param("groups[0].role", "HOST")
                        .param("groups[0].format", "{cluster}-{role}-{nn}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/series-templates"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(authAuditService).recordSeriesTemplateUpdated("operator", 7L, 1L, "Mon cluster");
    }

    @Test
    @WithMockUser(roles = "IP")
    void update_validationErrorsAndRejections_reshowForm() throws Exception {
        mvc.perform(post("/network/series-templates/7").with(csrf())
                        .param("contextId", "1").param("name", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("network/series-template-form"))
                .andExpect(model().attribute("currentRequestPath", "/network/series-templates/7/edit"));

        when(templateService.update(eq(7L), any()))
                .thenThrow(new InvalidSeriesException("bad"))
                .thenThrow(new ConflictException("exists"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/network/series-templates/7").with(csrf())
                            .param("contextId", "1").param("name", "Mon cluster")
                            .param("groups[0].role", "HOST").param("groups[0].format", "{nn}"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeExists("formError"));
        }
    }

    @Test
    @WithMockUser(roles = "IP")
    void update_unknownTemplate_returns404() throws Exception {
        when(templateService.update(eq(99L), any())).thenThrow(new ResourceNotFoundException("SeriesTemplate", 99L));

        mvc.perform(post("/network/series-templates/99").with(csrf())
                        .param("contextId", "1").param("name", "X")
                        .param("groups[0].role", "HOST").param("groups[0].format", "{nn}"))
                .andExpect(status().isNotFound());
    }

    // ── Suppression ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "operator", roles = "IP")
    void delete_removesAuditsAndRedirects() throws Exception {
        when(templateService.delete(7L)).thenReturn(custom);

        mvc.perform(post("/network/series-templates/7/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/series-templates"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(authAuditService).recordSeriesTemplateDeleted("operator", 7L, 1L, "Mon cluster");
    }

    @Test
    @WithMockUser(roles = "IP")
    void delete_unknownTemplate_flashesError() throws Exception {
        when(templateService.delete(99L)).thenThrow(new ResourceNotFoundException("SeriesTemplate", 99L));

        mvc.perform(post("/network/series-templates/99/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void delete_withoutIpRole_isForbidden() throws Exception {
        mvc.perform(post("/network/series-templates/7/delete").with(csrf()))
                .andExpect(status().isForbidden());

        verify(templateService, never()).delete(any());
    }
}
