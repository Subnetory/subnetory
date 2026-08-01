package dev.subnetory.web;

import dev.subnetory.dto.NetworkContextRequest;
import dev.subnetory.dto.NetworkContextResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.NetworkContextService;
import dev.subnetory.web.form.ContextForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
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
@RequestMapping("/network/contexts")
public class NetworkContextWebController {

    private static final int PAGE_SIZE = 20;

    private final NetworkContextService contextService;
    private final AuthAuditService authAuditService;

    public NetworkContextWebController(NetworkContextService contextService, AuthAuditService authAuditService) {
        this.contextService = contextService;
        this.authAuditService = authAuditService;
    }

    // ── Liste ──────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       Model model,
                       Authentication authentication) {
        model.addAttribute("contexts",
                contextService.findAll(PageRequest.of(page, PAGE_SIZE, Sort.by("name"))));
        model.addAttribute("canManage", canManage(authentication));
        model.addAttribute("activeSection", "contexts");
        model.addAttribute("pageTitle", "Contextes");
        return "network/contexts";
    }

    // ── Formulaire création ────────────────────────────────────────────────

    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String newForm(Model model) {
        prepareFormModel(model, new ContextForm(), "Nouveau contexte",
                "/network/contexts", "/network/contexts");
        return "network/context-form";
    }

    // ── Soumission création ────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@Valid @ModelAttribute("form") ContextForm form,
                         BindingResult errors,
                         Model model,
                         Authentication auth,
                         RedirectAttributes flash) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, "Nouveau contexte",
                    "/network/contexts", "/network/contexts");
            return "network/context-form";
        }
        try {
            NetworkContextResponse created =
                    contextService.create(new NetworkContextRequest(form.getName(), form.getDescription()));
            authAuditService.recordContextCreated(auth.getName(), created.id(), created.name());
            flash.addFlashAttribute("flashSuccess",
                    "Contexte « " + form.getName() + " » créé avec succès.");
        } catch (ConflictException e) {
            model.addAttribute("formError", "Un contexte portant ce nom existe déjà.");
            prepareFormModel(model, form, "Nouveau contexte",
                    "/network/contexts", "/network/contexts");
            return "network/context-form";
        }
        return "redirect:/network/contexts";
    }

    // ── Formulaire édition ─────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editForm(@PathVariable Long id,
                           Model model,
                           HttpServletResponse response) {
        try {
            prepareFormModel(model, ContextForm.from(contextService.findById(id)),
                    "Modifier le contexte",
                    "/network/contexts/" + id,
                    "/network/contexts");
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return "network/context-form";
    }

    // ── Soumission édition ─────────────────────────────────────────────────

    @PostMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") ContextForm form,
                         BindingResult errors,
                         Model model,
                         RedirectAttributes flash,
                         HttpServletResponse response) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, "Modifier le contexte",
                    "/network/contexts/" + id, "/network/contexts");
            // Piege POST/redirect (audit du 31/07/2026), meme pattern que
            // AddressWebController.prepareReserveModel().
            model.addAttribute("currentRequestPath", "/network/contexts/" + id + "/edit");
            return "network/context-form";
        }
        try {
            contextService.update(id, new NetworkContextRequest(form.getName(), form.getDescription()));
            flash.addFlashAttribute("flashSuccess", "Contexte mis à jour.");
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        } catch (ConflictException e) {
            model.addAttribute("formError", "Un contexte portant ce nom existe déjà.");
            prepareFormModel(model, form, "Modifier le contexte",
                    "/network/contexts/" + id, "/network/contexts");
            model.addAttribute("currentRequestPath", "/network/contexts/" + id + "/edit");
            return "network/context-form";
        }
        return "redirect:/network/contexts";
    }

    // ── Suppression ────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        try {
            NetworkContextResponse context = contextService.findById(id);
            contextService.delete(id);
            authAuditService.recordContextDeleted(auth.getName(), id, context.name());
            flash.addFlashAttribute("flashSuccess", "Contexte supprimé.");
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", "Contexte introuvable.");
        } catch (DataIntegrityViolationException e) {
            flash.addFlashAttribute("flashError",
                    "Impossible de supprimer : des sites sont rattachés à ce contexte.");
        }
        return "redirect:/network/contexts";
    }

    // ── Utilitaires privés ─────────────────────────────────────────────────

    private void prepareFormModel(Model model, ContextForm form,
                                  String pageTitle,
                                  String formAction,
                                  String cancelUrl) {
        model.addAttribute("form", form);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", formAction);
        model.addAttribute("cancelUrl", cancelUrl);
        model.addAttribute("activeSection", "contexts");
    }

    private boolean canManage(Authentication authentication) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
