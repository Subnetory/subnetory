package dev.subnetory.web;

import java.util.Locale;
import org.springframework.context.MessageSource;
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

    private final MessageSource messageSource;

    public HelpWebController(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    @GetMapping
    public String help(Model model, Locale locale) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", msg("pageTitle.help", locale));
        return "help";
    }

    @GetMapping("/prise-en-main")
    public String priseEnMain(Model model, Locale locale) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", msg("pageTitle.helpGettingStarted", locale));
        return "help/prise-en-main";
    }

    @GetMapping("/reseau")
    public String reseau(Model model, Locale locale) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", msg("pageTitle.helpNetwork", locale));
        return "help/reseau";
    }

    @GetMapping("/adresses-ip")
    public String adressesIp(Model model, Locale locale) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", msg("nav.addresses", locale));
        return "help/adresses-ip";
    }

    @GetMapping("/import-export")
    public String importExport(Model model, Locale locale) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", msg("pageTitle.helpImportExport", locale));
        return "help/import-export";
    }

    @GetMapping("/compte-securite")
    public String compteSecurite(Model model, Locale locale) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", msg("pageTitle.helpAccount", locale));
        return "help/compte-securite";
    }

    @GetMapping("/api")
    public String api(Model model, Locale locale) {
        model.addAttribute("activeSection", "help");
        model.addAttribute("pageTitle", msg("pageTitle.helpApi", locale));
        return "help/api";
    }
}
