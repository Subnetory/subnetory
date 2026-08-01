package dev.subnetory.web;

import dev.subnetory.dto.NetworkContextResponse;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.NetworkContextService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
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
            model.addAttribute("currentRequestPath", safeCurrentPath(request.getRequestURI()));
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
        model.addAttribute("currentRequestPath", safeCurrentPath(request.getRequestURI()));
    }

    private String safeCurrentPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) return "/";
        return path;
    }
}
