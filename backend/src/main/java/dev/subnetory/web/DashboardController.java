package dev.subnetory.web;

import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.DashboardService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller de la page d'accueil — dashboard statistiques.
 *
 * <p>Accessible à tout utilisateur authentifié (aucune restriction de rôle).
 * La sécurité est gérée par la chaîne Spring Security (anyRequest().authenticated()).</p>
 */
@Controller
public class DashboardController {

    private final DashboardService dashboardService;
    private final ActiveContextService activeContextService;

    public DashboardController(DashboardService dashboardService,
                               ObjectProvider<ActiveContextService> activeContextServiceProvider) {
        this.dashboardService = dashboardService;
        this.activeContextService = activeContextServiceProvider.getIfAvailable();
    }

    /**
     * Page d'accueil — dashboard.
     *
     * <p>Remplace l'ancienne redirection vers {@code /network/subnets}.
     * Calcule les statistiques globales et les transmet au template.</p>
     */
    @GetMapping("/")
    public String dashboard(Model model, HttpSession session) {
        Long activeContextId = activeContextService == null ? null : activeContextService.get(session);
        model.addAttribute("stats", dashboardService.getStats(activeContextId));
        model.addAttribute("activeSection", "dashboard");
        model.addAttribute("pageTitle", "Tableau de bord");
        return "dashboard";
    }
}
