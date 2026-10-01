package dev.subnetory.web;

import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.GlobalSearchService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;

/** Page de résultats de la recherche globale du bandeau. */
@Controller
@RequestMapping("/search")
public class SearchWebController {

    private static final int MAX_QUERY_LENGTH = 120;

    private final GlobalSearchService searchService;
    private final ActiveContextService activeContextService;
    private final MessageSource messageSource;

    public SearchWebController(GlobalSearchService searchService,
                               ObjectProvider<ActiveContextService> activeContextServiceProvider,
                               MessageSource messageSource) {
        this.searchService = searchService;
        this.activeContextService = activeContextServiceProvider.getIfAvailable();
        this.messageSource = messageSource;
    }

    @GetMapping
    public String search(@RequestParam(required = false) String q,
                         Authentication auth,
                         Model model,
                         HttpSession session,
                         Locale locale) {
        String query = q == null ? "" : q.trim();
        if (query.length() > MAX_QUERY_LENGTH) query = query.substring(0, MAX_QUERY_LENGTH);
        Long activeContextId = activeContextService == null ? null : activeContextService.get(session);

        if (!query.isEmpty()) {
            model.addAttribute("result", searchService.search(query, activeContextId));
        }
        model.addAttribute("query", query);
        model.addAttribute("globalQuery", query);
        model.addAttribute("limit", GlobalSearchService.LIMIT_PER_TYPE);
        model.addAttribute("canManageNetwork", hasAnyRole(auth, "ROLE_ADMIN", "ROLE_NETWORK"));
        model.addAttribute("canManageIp", hasAnyRole(auth, "ROLE_ADMIN", "ROLE_IP"));
        model.addAttribute("canManageContexts", hasAnyRole(auth, "ROLE_ADMIN"));
        model.addAttribute("returnTo", ReturnTo.currentUrl());
        model.addAttribute("activeSection", "search");
        model.addAttribute("pageTitle", messageSource.getMessage("search.title", null, locale));
        return "network/search";
    }

    private static boolean hasAnyRole(Authentication auth, String... roles) {
        if (auth == null) return false;
        for (var granted : auth.getAuthorities()) {
            for (String role : roles) {
                if (role.equals(granted.getAuthority())) return true;
            }
        }
        return false;
    }
}
