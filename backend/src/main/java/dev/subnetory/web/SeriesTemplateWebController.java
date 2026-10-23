package dev.subnetory.web;

import dev.subnetory.dto.NetworkContextResponse;
import dev.subnetory.dto.SeriesTemplateRequest;
import dev.subnetory.dto.SeriesTemplateResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.NetworkContextService;
import dev.subnetory.service.SeriesTemplateService;
import dev.subnetory.web.form.SeriesTemplateForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;

/**
 * Gestion des templates de réservation en série : presets livrés (lecture seule, à
 * dupliquer) et templates personnalisés du contexte (création, modification, suppression
 * pour les rôles ADMIN et IP). Toute la logique est dans {@link SeriesTemplateService},
 * partagée avec l'API REST.
 */
@Controller
@RequestMapping("/network/series-templates")
public class SeriesTemplateWebController {

    private static final String LIST_URL = "/network/series-templates";
    private static final String FORM_VIEW = "network/series-template-form";

    private final SeriesTemplateService templateService;
    private final NetworkContextService contextService;
    private final ActiveContextService activeContextService;
    private final AuthAuditService authAuditService;
    private final MessageSource messageSource;

    public SeriesTemplateWebController(SeriesTemplateService templateService,
                                       NetworkContextService contextService,
                                       ObjectProvider<ActiveContextService> activeContextServiceProvider,
                                       AuthAuditService authAuditService,
                                       MessageSource messageSource) {
        this.templateService = templateService;
        this.contextService = contextService;
        this.activeContextService = activeContextServiceProvider.getIfAvailable();
        this.authAuditService = authAuditService;
        this.messageSource = messageSource;
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    // ── Liste ──────────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model, Authentication authentication, Locale locale) {
        Long activeContextId = activeContextService == null
                ? null : activeContextService.getCurrentRequestContext();
        model.addAttribute("templates", templateService.list(activeContextId));
        model.addAttribute("canManage", canManage(authentication));
        model.addAttribute("activeSection", "addresses");
        model.addAttribute("pageTitle", msg("pageTitle.seriesTemplates", locale));
        return "network/series-templates";
    }

    // ── Création ───────────────────────────────────────────────────────────

    /** Formulaire vide, ou pré-rempli depuis un template existant ({@code from}) pour le dupliquer. */
    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String newForm(@RequestParam(required = false) String from,
                          Model model,
                          HttpServletResponse response,
                          Locale locale) {
        SeriesTemplateForm form = new SeriesTemplateForm();
        if (from != null && !from.isBlank()) {
            try {
                SeriesTemplateResponse source = templateService.get(from);
                form = SeriesTemplateForm.from(source);
                form.setId(null);
                form.setName(msg("seriesTemplate.copyName", locale, source.name()));
            } catch (ResourceNotFoundException e) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return "error/404";
            }
        }
        prepareFormModel(model, form, msg("pageTitle.seriesTemplateNew", locale), LIST_URL);
        return FORM_VIEW;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String create(@Valid @ModelAttribute("form") SeriesTemplateForm form,
                         BindingResult errors,
                         Model model,
                         Authentication auth,
                         RedirectAttributes flash,
                         Locale locale) {
        if (errors.hasErrors()) {
            return formAgain(model, form, locale, "pageTitle.seriesTemplateNew", LIST_URL, "/new");
        }
        try {
            SeriesTemplateResponse created = templateService.create(form.toRequest());
            authAuditService.recordSeriesTemplateCreated(auth.getName(), created.id(),
                    created.contextId(), created.name());
            flash.addFlashAttribute("flashSuccess",
                    msg("flash.seriesTemplate.createSuccess", locale, created.name()));
        } catch (InvalidSeriesException e) {
            model.addAttribute("formError", msg("flash.seriesTemplate.invalid", locale, e.getMessage()));
            return formAgain(model, form, locale, "pageTitle.seriesTemplateNew", LIST_URL, "/new");
        } catch (ConflictException e) {
            model.addAttribute("formError", msg("flash.seriesTemplate.nameConflict", locale, form.getName()));
            return formAgain(model, form, locale, "pageTitle.seriesTemplateNew", LIST_URL, "/new");
        } catch (ResourceNotFoundException e) {
            model.addAttribute("formError", msg("flash.seriesTemplate.contextNotFound", locale));
            return formAgain(model, form, locale, "pageTitle.seriesTemplateNew", LIST_URL, "/new");
        }
        return "redirect:" + LIST_URL;
    }

    // ── Modification ───────────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String editForm(@PathVariable Long id,
                           Model model,
                           HttpServletResponse response,
                           Locale locale) {
        try {
            SeriesTemplateForm form = SeriesTemplateForm.from(templateService.get("custom:" + id));
            prepareFormModel(model, form, msg("pageTitle.seriesTemplateEdit", locale), LIST_URL);
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return FORM_VIEW;
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") SeriesTemplateForm form,
                         BindingResult errors,
                         Model model,
                         Authentication auth,
                         RedirectAttributes flash,
                         HttpServletResponse response,
                         Locale locale) {
        form.setId(id);
        if (errors.hasErrors()) {
            return formAgain(model, form, locale, "pageTitle.seriesTemplateEdit", LIST_URL, "/" + id + "/edit");
        }
        try {
            SeriesTemplateRequest request = form.toRequest();
            SeriesTemplateResponse updated = templateService.update(id, request);
            authAuditService.recordSeriesTemplateUpdated(auth.getName(), updated.id(),
                    updated.contextId(), updated.name());
            flash.addFlashAttribute("flashSuccess", msg("flash.seriesTemplate.updateSuccess", locale));
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        } catch (InvalidSeriesException e) {
            model.addAttribute("formError", msg("flash.seriesTemplate.invalid", locale, e.getMessage()));
            return formAgain(model, form, locale, "pageTitle.seriesTemplateEdit", LIST_URL, "/" + id + "/edit");
        } catch (ConflictException e) {
            model.addAttribute("formError", msg("flash.seriesTemplate.nameConflict", locale, form.getName()));
            return formAgain(model, form, locale, "pageTitle.seriesTemplateEdit", LIST_URL, "/" + id + "/edit");
        }
        return "redirect:" + LIST_URL;
    }

    // ── Suppression ────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes flash, Locale locale) {
        try {
            SeriesTemplateResponse deleted = templateService.delete(id);
            authAuditService.recordSeriesTemplateDeleted(auth.getName(), deleted.id(),
                    deleted.contextId(), deleted.name());
            flash.addFlashAttribute("flashSuccess", msg("flash.seriesTemplate.deleteSuccess", locale));
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.seriesTemplate.notFound", locale));
        }
        return "redirect:" + LIST_URL;
    }

    // ── Utilitaires privés ─────────────────────────────────────────────────

    /**
     * Réaffiche le formulaire après une erreur. La vue est rendue directement sur l'URL POST,
     * qui n'a pas de GET jumeau : {@code currentRequestPath} pointe vers la page GET stable
     * pour que le sélecteur de langue et de contexte ne provoque pas de 405.
     */
    private String formAgain(Model model, SeriesTemplateForm form, Locale locale, String titleKey,
                             String cancelUrl, String getPathSuffix) {
        prepareFormModel(model, form, msg(titleKey, locale), cancelUrl);
        model.addAttribute("currentRequestPath", LIST_URL + getPathSuffix);
        return FORM_VIEW;
    }

    private void prepareFormModel(Model model, SeriesTemplateForm form, String pageTitle, String cancelUrl) {
        model.addAttribute("form", form);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", form.getId() == null ? LIST_URL : LIST_URL + "/" + form.getId());
        model.addAttribute("cancelUrl", cancelUrl);
        model.addAttribute("activeSection", "addresses");
        Long activeContextId = activeContextService == null
                ? null : activeContextService.getCurrentRequestContext();
        if (form.getContextId() == null && activeContextId != null) {
            form.setContextId(activeContextId);
        }
        List<NetworkContextResponse> contexts = activeContextId == null
                ? contextService.findAll(Pageable.unpaged()).getContent()
                : List.of(contextService.findById(activeContextId));
        model.addAttribute("allContexts", contexts);
    }

    private boolean canManage(Authentication authentication) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                            || "ROLE_IP".equals(a.getAuthority()));
    }
}
