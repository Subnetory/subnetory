package dev.subnetory.api.v1;

import dev.subnetory.dto.DashboardStats;
import dev.subnetory.service.DashboardService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardApiControllerTest {

    private final DashboardService dashboardService = mock(DashboardService.class);
    private final DashboardApiController controller = new DashboardApiController(dashboardService);

    @Test
    void statsDelegatesToDashboardServiceWithOptionalContext() {
        DashboardStats stats = new DashboardStats(1, 2, 3, 4, 5, List.of());
        when(dashboardService.getStats(7L)).thenReturn(stats);

        assertThat(controller.stats(7L)).isEqualTo(stats);
        verify(dashboardService).getStats(7L);
    }
}
