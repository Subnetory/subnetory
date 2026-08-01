package dev.subnetory.web;

import dev.subnetory.dto.VlanRequest;
import dev.subnetory.dto.VlanResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.SiteService;
import dev.subnetory.service.VlanService;
import dev.subnetory.web.form.VlanForm;
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
@RequestMapping("/network/vlans")
public class VlanWebController {

    private static final int PAGE_SIZE = 20;

    private final VlanService vlanService;
    private final SiteService siteService;
    private final ActiveContextService activeContextService;
    private final AuthAuditService authAuditService;

    public VlanWebController(VlanService vlanService,
                             SiteService siteService,
                             ObjectProvider<ActiveContextService> activeContextServiceProvider,
                             AuthAuditService authAuditService) {
        this.vlanService = vlanService;
        this.siteService = siteService;
        this.activeContextService = activeContextServiceProvider.getIfAvailable();
        this.authAuditService = authAuditService;
    }

    // ── Liste ──────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Long siteId,
                       Model model,
                       Authentication authentication,
                       HttpSession session) {
        var pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("vid"));
        Long activeContextId = activeContextService == null ? null : activeContextService.get(session);
        if (siteId != null) {
            var site = siteService.findById(siteId);
            if (activeContextId != null && !activeContextId.equals(site.contextId())) {
                throw new ResourceNotFoundException("Site", siteId);
            }
            model.addAttribute("vlans", vlanService.findBySite(siteId, pageable));
        } else if (activeContextId != null) {
            model.addAttribute("vlans", vlanService.findByContext(activeContextId, pageable));
        } else {
            model.addAttribute("vlans", vlanService.findAll(pageable));
        }
        model.addAttribute("sites", activeContextId == null
                ? siteService.findAll(PageRequest.of(0, 200, Sort.by("code")))
                : siteService.findByContext(activeContextId, PageRequest.of(0, 200, Sort.by("code"))));
        model.addAttribute("selectedSiteId", siteId);
        model.addAttribute("canManage", canManage(authentication));
        model.addAttribute("activeSection", "vlans");
        model.addAttribute("pageTitle", "VLANs");
        return "network/vlans";
    }

    // ── Formulaire création ────────────────────────────────────────────────

    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String newForm(Model model) {
        prepareFormModel(model, new VlanForm(), "Nouveau VLAN",
                "/network/vlans", "/network/vlans");
        return "network/vlan-form";
    }

    // ── Soumission création ────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String create(@Valid @ModelAttribute("form") VlanForm form,
                         BindingResult errors,
                         Model model,
                         Authentication auth,
                         RedirectAttributes flash) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, "Nouveau VLAN",
                    "/network/vlans", "/network/vlans");
            return "network/vlan-form";
        }
        try {
            VlanResponse created = vlanService.create(new VlanRequest(form.getName(), form.getVid(), form.getSiteId()));
            authAuditService.recordVlanCreated(auth.getName(), created.id(),
                    "VLAN " + created.vid() + " (" + created.name() + ")");
            flash.addFlashAttribute("flashSuccess",
                    "VLAN " + form.getVid() + " créé avec succès.");
        } catch (ConflictException e) {
            model.addAttribute("formError",
                    "Le VLAN " + form.getVid() + " existe déjà sur ce site.");
            prepareFormModel(model, form, "Nouveau VLAN",
                    "/network/vlans", "/network/vlans");
            return "network/vlan-form";
        }
        return "redirect:/network/vlans";
    }

    // ── Formulaire édition ─────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String editForm(@PathVariable Long id,
                           Model model,
                           HttpServletResponse response) {
        try {
            prepareFormModel(model, VlanForm.from(vlanService.findById(id)),
                    "Modifier le VLAN",
                    "/network/vlans/" + id,
                    "/network/vlans");
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return "network/vlan-form";
    }

    // ── Soumission édition ─────────────────────────────────────────────────

    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") VlanForm form,
                         BindingResult errors,
                         Model model,
                         RedirectAttributes flash,
                         HttpServletResponse response) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, "Modifier le VLAN",
                    "/network/vlans/" + id, "/network/vlans");
            // Piege POST/redirect (audit du 31/07/2026), meme pattern que
            // AddressWebController.prepareReserveModel().
            model.addAttribute("currentRequestPath", "/network/vlans/" + id + "/edit");
            return "network/vlan-form";
        }
        try {
            vlanService.update(id, new VlanRequest(form.getName(), form.getVid(), form.getSiteId()));
            flash.addFlashAttribute("flashSuccess", "VLAN mis à jour.");
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        } catch (ConflictException e) {
            model.addAttribute("formError",
                    "Le VLAN " + form.getVid() + " existe déjà sur ce site.");
            prepareFormModel(model, form, "Modifier le VLAN",
                    "/network/vlans/" + id, "/network/vlans");
            model.addAttribute("currentRequestPath", "/network/vlans/" + id + "/edit");
            return "network/vlan-form";
        }
        return "redirect:/network/vlans";
    }

    // ── Suppression ────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        try {
            VlanResponse vlan = vlanService.findById(id);
            vlanService.delete(id);
            authAuditService.recordVlanDeleted(auth.getName(), id,
                    "VLAN " + vlan.vid() + " (" + vlan.name() + ")");
            flash.addFlashAttribute("flashSuccess", "VLAN supprimé.");
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", "VLAN introuvable.");
        } catch (DataIntegrityViolationException e) {
            flash.addFlashAttribute("flashError",
                    "Impossible de supprimer : des sous-réseaux sont rattachés à ce VLAN.");
        }
        return "redirect:/network/vlans";
    }

    // ── Utilitaires privés ─────────────────────────────────────────────────

    private void prepareFormModel(Model model, VlanForm form,
                                  String pageTitle,
                                  String formAction,
                                  String cancelUrl) {
        model.addAttribute("form", form);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", formAction);
        model.addAttribute("cancelUrl", cancelUrl);
        model.addAttribute("activeSection", "vlans");
        Long activeContextId = activeContextService == null
                ? null : activeContextService.getCurrentRequestContext();
        model.addAttribute("allSites", activeContextId == null
                ? siteService.findAll(Pageable.unpaged())
                : siteService.findByContext(activeContextId, Pageable.unpaged()));
    }

    private boolean canManage(Authentication authentication) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                            || "ROLE_NETWORK".equals(a.getAuthority()));
    }
}
