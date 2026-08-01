package dev.subnetory.web;

import dev.subnetory.service.ActiveContextService;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ContextSelectionController {

    private final ActiveContextService activeContextService;

    public ContextSelectionController(ActiveContextService activeContextService) {
        this.activeContextService = activeContextService;
    }

    @PostMapping("/context-selection")
    public String select(@RequestParam Long contextId,
                         @RequestParam(required = false) String returnTo,
                         HttpSession session) {
        activeContextService.select(session, contextId);
        return "redirect:" + safeReturnTo(returnTo);
    }

    @PostMapping("/context-selection/reset")
    public String reset(@RequestParam(required = false) String returnTo,
                        HttpSession session) {
        activeContextService.reset(session);
        return "redirect:" + safeReturnTo(returnTo);
    }

    private String safeReturnTo(String value) {
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) {
            return "/";
        }
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() || uri.getHost() != null || !value.startsWith("/")
                    || value.startsWith("//")) {
                return "/";
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) return "/";
            return path;
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }
}
