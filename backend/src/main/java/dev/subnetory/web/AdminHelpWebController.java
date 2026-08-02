package dev.subnetory.web;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Guide administrateur &amp; deploiement (01/08/2026) : sommaire (hub) et
 * articles couvrant utilisateurs/roles/MFA, audit, sauvegardes, et
 * deploiement Docker Compose/HTTPS/Kubernetes.
 *
 * Reserve ROLE_ADMIN : herite de la regle de securite existante sur
 * /admin/** (SecurityConfig#webFilterChain), aucune regle dediee necessaire
 * ici. A la difference de /admin/backup/**, aucun acces n'est ouvert a
 * ROLE_BACKUP — ces pages couvrent des sujets sensibles (secrets, MFA de
 * secours, deploiement) qui depassent le perimetre d'un compte de service
 * dedie aux sauvegardes.
 */
@Controller
@RequestMapping("/admin/help")
public class AdminHelpWebController {

    private final MessageSource messageSource;

    public AdminHelpWebController(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    @GetMapping
    public String hub(Model model, Locale locale) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminHelp", locale));
        return "admin/help";
    }

    @GetMapping("/utilisateurs-roles")
    public String utilisateursRoles(Model model, Locale locale) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminHelpUsersRoles", locale));
        return "admin/help/utilisateurs-roles";
    }

    @GetMapping("/audit")
    public String audit(Model model, Locale locale) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminHelpAudit", locale));
        return "admin/help/audit";
    }

    @GetMapping("/sauvegardes")
    public String sauvegardes(Model model, Locale locale) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminHelpBackup", locale));
        return "admin/help/sauvegardes";
    }

    @GetMapping("/deploiement-docker")
    public String deploiementDocker(Model model, Locale locale) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminHelpDocker", locale));
        return "admin/help/deploiement-docker";
    }

    @GetMapping("/deploiement-kubernetes")
    public String deploiementKubernetes(Model model, Locale locale) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminHelpKubernetes", locale));
        return "admin/help/deploiement-kubernetes";
    }
}
