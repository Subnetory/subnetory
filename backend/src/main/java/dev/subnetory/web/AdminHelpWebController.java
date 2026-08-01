package dev.subnetory.web;

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

    @GetMapping
    public String hub(Model model) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Guide administrateur");
        return "admin/help";
    }

    @GetMapping("/utilisateurs-roles")
    public String utilisateursRoles(Model model) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Utilisateurs, rôles et MFA");
        return "admin/help/utilisateurs-roles";
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Audit et journalisation");
        return "admin/help/audit";
    }

    @GetMapping("/sauvegardes")
    public String sauvegardes(Model model) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Sauvegarde et restauration");
        return "admin/help/sauvegardes";
    }

    @GetMapping("/deploiement-docker")
    public String deploiementDocker(Model model) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Déploiement Docker Compose et HTTPS");
        return "admin/help/deploiement-docker";
    }

    @GetMapping("/deploiement-kubernetes")
    public String deploiementKubernetes(Model model) {
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Déploiement Kubernetes");
        return "admin/help/deploiement-kubernetes";
    }
}
