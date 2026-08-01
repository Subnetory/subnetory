package dev.subnetory.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Centre d'aide integre a l'application (01/08/2026) : sommaire (hub) et
 * articles couvrant la prise en main, l'organisation du reseau, les
 * adresses IP, l'import/export, le compte utilisateur (MFA) et l'API.
 *
 * Accessible a tout utilisateur authentifie, aucune restriction de role
 * (contrairement au pendant administrateur/deploiement sous /admin/help,
 * qui herite de la regle de securite existante sur /admin/**).
 */
@Controller
@RequestMapping("/help")
public class HelpWebController {

    @GetMapping
    public String help(Model model) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", "Aide");
        return "help";
    }

    @GetMapping("/prise-en-main")
    public String priseEnMain(Model model) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", "Prise en main");
        return "help/prise-en-main";
    }

    @GetMapping("/reseau")
    public String reseau(Model model) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", "Contextes, sites, VLAN et sous-réseaux");
        return "help/reseau";
    }

    @GetMapping("/adresses-ip")
    public String adressesIp(Model model) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", "Adresses IP");
        return "help/adresses-ip";
    }

    @GetMapping("/import-export")
    public String importExport(Model model) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", "Import et export");
        return "help/import-export";
    }

    @GetMapping("/compte-securite")
    public String compteSecurite(Model model) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", "Mon compte et sécurité");
        return "help/compte-securite";
    }

    @GetMapping("/api")
    public String api(Model model) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", "API et automatisation");
        return "help/api";
    }
}
