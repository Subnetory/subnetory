package dev.subnetory.api.v1;

import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.dto.AdminUserCreateRequest;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.service.UserTokenInvalidationService;
import dev.subnetory.security.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserControllerTest {

    private final UserAdminService userAdminService = mock(UserAdminService.class);
    private final UserTokenInvalidationService tokenInvalidationService = mock(UserTokenInvalidationService.class);
    private final AuthAuditService authAuditService = mock(AuthAuditService.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final AdminUserController controller = new AdminUserController(
            userAdminService,
            tokenInvalidationService,
            authAuditService,
            clientIpResolver);

    @Test
    void detailReturnsUserWithoutPasswordHash() {
        User user = user();
        user.setPassword("$2a$secret");
        when(userAdminService.findById(10L)).thenReturn(user);

        var response = controller.detail(10L);

        assertThat(response.username()).isEqualTo("operator");
        assertThat(response.roles()).containsExactly("ROLE_IP");
        assertThat(response.toString()).doesNotContain("$2a$secret");
    }

    @Test
    void createDelegatesToUserAdminServiceWithAuthenticatedAdmin() {
        User user = user();
        when(userAdminService.createLocalUser(any(), any(), any(), any(Boolean.class), any(), any(), any()))
                .thenReturn(user);

        var request = new AdminUserCreateRequest(
                "operator",
                "operator@example.com",
                "TempPass123!",
                true,
                Set.of(3L),
                Set.of(1L));

        var response = controller.create(request,
                new UsernamePasswordAuthenticationToken("admin", "n/a"));

        assertThat(response.username()).isEqualTo("operator");
        verify(userAdminService).createLocalUser(
                "operator",
                "operator@example.com",
                "TempPass123!",
                true,
                Set.of(3L),
                Set.of(1L),
                "admin");
    }

    @Test
    void assignableRolesExposeReadOnlyDescription() {
        Role role = new Role("ROLE_READ_ONLY");
        role.setId(4L);
        when(userAdminService.findAssignableRoles()).thenReturn(List.of(role));

        var response = controller.assignableRoles();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().name()).isEqualTo("ROLE_READ_ONLY");
        assertThat(response.getFirst().label()).isEqualTo("READ_ONLY");
        assertThat(response.getFirst().description()).contains("Lecture seule");
    }

    @Test
    void disableMfaDelegatesToUserAdminService() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        controller.disableMfa(10L,
                new UsernamePasswordAuthenticationToken("admin", "n/a"),
                request);

        verify(userAdminService).adminDisableMfa(10L, "admin", "127.0.0.1", "JUnit");
    }

    @Test
    void deleteDelegatesToUserAdminService() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        controller.delete(10L,
                new UsernamePasswordAuthenticationToken("admin", "n/a"),
                request);

        verify(userAdminService).deleteUser(10L, "admin", "127.0.0.1", "JUnit");
    }

    private User user() {
        Role role = new Role("ROLE_IP");
        User user = new User();
        user.setId(10L);
        user.setUsername("operator");
        user.setAuthType("LOCAL");
        user.setEnabled(true);
        user.getRoles().add(role);
        return user;
    }
}
