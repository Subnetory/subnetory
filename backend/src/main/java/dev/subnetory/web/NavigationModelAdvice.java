package dev.subnetory.web;

import dev.subnetory.dto.NetworkContextResponse;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.NetworkContextService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;

/** Donnees communes de navigation pour toutes les pages Thymeleaf. */
@ControllerAdvice(basePackages = "dev.subnetory.web")
public class NavigationModelAdvice {

    private final ObjectProvider<NetworkContextService> contextServiceProvider;
    private final ObjectProvider<ActiveContextService> activeContextServiceProvider;

    public NavigationModelAdvice(ObjectProvider<NetworkContextService> contextServiceProvider,
                                 ObjectProvider<ActiveContextService> activeContextServiceProvider) {
        this.contextServiceProvider = contextServiceProvider;
        this.activeContextServiceProvider = activeContextServiceProvider;
    }

    @ModelAttribute
    public void addNavigationModel(Model model,
                                   Authentication authentication,
                                   HttpSession session,
                                   HttpServletRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            model.addAttribute("navigationContexts", List.of());
            return;
        }

        NetworkContextService contextService = contextServiceProvider.getIfAvailable();
        ActiveContextService activeContextService = activeContextServiceProvider.getIfAvailable();
        if (contextService == null || activeContextService == null) {
            model.addAttribute("navigationContexts", List.of());
            model.addAttribute("currentRequestPath", currentRequestPathWithQuery(request));
            return;
        }

        List<NetworkContextResponse> contexts = contextService.findAllAccessible();
        Long activeId = activeContextService.get(session);
        NetworkContextResponse active = contexts.stream()
                .filter(context -> context.id().equals(activeId))
                .findFirst()
                .orElse(null);

        model.addAttribute("navigationContexts", contexts);
        model.addAttribute("activeContextId", activeId);
        model.addAttribute("activeContextName", active == null ? null : active.name());
        model.addAttribute("currentRequestPath", currentRequestPathWithQuery(request));
    }

    private String safeCurrentPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) return "/";
        return path;
    }

    /**
     * Correctif MOYENNE (02/08/2026) : {@code currentRequestPath} n'incluait
     * jusqu'ici que le chemin (request.getRequestURI()), jamais la chaine de
     * requete. Le lien de bascule de langue de layout/base.html
     * (@{${currentRequestPath}(lang='fr')}) perdait donc silencieusement tous
     * les filtres/la pagination actifs (recherche du journal d'audit, liste
     * d'adresses filtree, page 2+) a chaque changement de langue, en
     * retombant sur la page 1 sans filtre.
     *
     * <p>Utilise volontairement {@code request.getQueryString()} (la chaine
     * brute de l'URL) plutot que {@code request.getParameterMap()} : ce
     * dernier inclut aussi les parametres de corps d'une requete POST
     * (ex. reaffichage d'un formulaire en erreur), ce qui aurait fait fuiter
     * des champs de formulaire dans l'URL de bascule de langue. Le parametre
     * {@code lang} deja present est retire pour eviter de l'ajouter en
     * double lors de bascules successives.</p>
     */
    // Package-private (et non private) pour permettre un test unitaire
    // direct sans passer par le cycle de vie complet @ModelAttribute/MockMvc.
    String currentRequestPathWithQuery(HttpServletRequest request) {
        String path = safeCurrentPath(request.getRequestURI());
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return path;
        }
        String withoutLang = Arrays.stream(query.split("&"))
                .filter(param -> !param.equals("lang") && !param.startsWith("lang="))
                .collect(Collectors.joining("&"));
        return withoutLang.isEmpty() ? path : path + "?" + withoutLang;
    }
}
