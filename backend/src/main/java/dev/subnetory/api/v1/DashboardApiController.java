package dev.subnetory.api.v1;

import dev.subnetory.dto.DashboardStats;
import dev.subnetory.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Dashboard", description = "Statistiques globales et par contexte")
public class DashboardApiController {

    private final DashboardService dashboardService;

    public DashboardApiController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "Consulter les statistiques du tableau de bord")
    public DashboardStats stats(@RequestParam(required = false) Long contextId) {
        return dashboardService.getStats(contextId);
    }
}
