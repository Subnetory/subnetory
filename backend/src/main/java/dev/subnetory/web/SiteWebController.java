package dev.subnetory.web;

import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SiteResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.NetworkContextService;
import dev.subnetory.service.SiteService;
import dev.subnetory.web.form.SiteForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

@Controller
@RequestMapping("/network/sites")
public class SiteWebController {

    private static final int PAGE_SIZE = 20;

    private final SiteService siteService;
    private final NetworkContextService contextService;
    private final ActiveContextService activeContextService;
    private final AuthAuditService authAuditService;

    public SiteWebController(SiteService siteService,
                             NetworkContextService contextService,
                             ObjectProvider<ActiveContextService> activeContextServiceProvider,
                             AuthAuditService authAuditService) {
        this.siteService    = siteService;
        this.contextService = contextService;
        this.activeContextService = activeContextServiceProvider.getIfAvailable();
        this.authAuditService = authAuditService;
    }

    // ── Liste ──────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Long contextId,
                       Model model,
                       Authentication authentication,
                       HttpSession session) {
        var pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("code"));
        Long selectedContextId = activeContextService == null
                ? contextId : activeContextService.resolve(session, contextId);
        if (selectedContextId != null) {
            model.addAttribute("sites", siteService.findByContext(selectedContextId, pageable));
        } else {
            model.addAttribute("sites", siteService.findAll(pageable));
        }
        model.addAttribute("contexts", contextService.findAll(PageRequest.of(0, 100, Sort.by("name"))));
        model.addAttribute("selectedContextId", selectedContextId);
        model.addAttribute("canManage", canManage(authentication));
        model.addAttribute("activeSection", "sites");
        model.addAttribute("pageTitle", "Sites");
        return "network/sites";
    }

    // ── Formulaire création ────────────────────────────────────────────────

    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String newForm(Model model) {
        prepareFormModel(model, new SiteForm(), "Nouveau site",
                "/network/sites", "/network/sites");
        return "network/site-form";
    }

    // ── Soumission création ────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String create(@Valid @ModelAttribute("form") SiteForm form,
                         BindingResult errors,
                         Model model,
                         Authentication auth,
                         RedirectAttributes flash) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, "Nouveau site",
                    "/network/sites", "/network/sites");
            return "network/site-form";
        }
        try {
            SiteResponse created =
                    siteService.create(new SiteRequest(form.getName(), form.getCode(), form.getContextId()));
            authAuditService.recordSiteCreated(auth.getName(), created.id(), created.name());
            flash.addFlashAttribute("flashSuccess",
                    "Site « " + form.getName() + " » créé avec succès.");
        } catch (ConflictException e) {
            model.addAttribute("formError",
                    "Un site avec le code « " + form.getCode() + " » existe déjà.");
            prepareFormModel(model, form, "Nouveau site",
                    "/network/sites", "/network/sites");
            return "network/site-form";
        }
        return "redirect:/network/sites";
    }

    // ── Formulaire édition ─────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String editForm(@PathVariable Long id,
                           Model model,
                           HttpServletResponse response) {
        try {
            prepareFormModel(model, SiteForm.from(siteService.findById(id)),
                    "Modifier le site",
                    "/network/sites/" + id,
                    "/network/sites");
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return "network/site-form";
    }

    // ── Soumission édition ─────────────────────────────────────────────────

    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") SiteForm form,
                         BindingResult errors,
                         Model model,
                         RedirectAttributes flash,
                         HttpServletResponse response) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, "Modifier le site",
                    "/network/sites/" + id, "/network/sites");
            // Piege POST/redirect (audit du 31/07/2026) : cette vue est rendue
            // directement sur l'URL POST /network/sites/{id}, qui n'a aucun
            // @GetMapping jumeau (seul /{id}/edit existe). Sans cette
            // surcharge, un changement de contexte juste apres declenche un
            // GET 405 -> 500 (cf. GlobalExceptionHandler). Meme pattern que
            // AddressWebController.prepareReserveModel().
            model.addAttribute("currentRequestPath", "/network/sites/" + id + "/edit");
            return "network/site-form";
        }
        try {
            siteService.update(id, new SiteRequest(form.getName(), form.getCode(), form.getContextId()));
            flash.addFlashAttribute("flashSuccess", "Site mis à jour.");
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        } catch (ConflictException e) {
            model.addAttribute("formError",
                    "Un site avec le code « " + form.getCode() + " » existe déjà.");
            prepareFormModel(model, form, "Modifier le site",
                    "/network/sites/" + id, "/network/sites");
            model.addAttribute("currentRequestPath", "/network/sites/" + id + "/edit");
            return "network/site-form";
        }
        return "redirect:/network/sites";
    }

    // ── Suppression ────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        try {
            SiteResponse site = siteService.findById(id);
            siteService.delete(id);
            authAuditService.recordSiteDeleted(auth.getName(), id, site.name());
            flash.addFlashAttribute("flashSuccess", "Site supprimé.");
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", "Site introuvable.");
        } catch (DataIntegrityViolationException e) {
            flash.addFlashAttribute("flashError",
                    "Impossible de supprimer : des sous-réseaux ou VLANs sont rattachés à ce site.");
        }
        return "redirect:/network/sites";
    }

    // ── Utilitaires privés ─────────────────────────────────────────────────

    private void prepareFormModel(Model model, SiteForm form,
                                  String pageTitle,
                                  String formAction,
                                  String cancelUrl) {
        model.addAttribute("form", form);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", formAction);
        model.addAttribute("cancelUrl", cancelUrl);
        model.addAttribute("activeSection", "sites");
        Long activeContextId = activeContextService == null
                ? null : activeContextService.getCurrentRequestContext();
        if (form.getContextId() == null && activeContextId != null) form.setContextId(activeContextId);
        model.addAttribute("allContexts", activeContextId == null
                ? contextService.findAll(Pageable.unpaged())
                : java.util.List.of(contextService.findById(activeContextId)));
    }

    private boolean canManage(Authentication authentication) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                            || "ROLE_NETWORK".equals(a.getAuthority()));
    }
}
