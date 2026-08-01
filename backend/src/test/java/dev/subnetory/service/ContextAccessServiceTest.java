package dev.subnetory.service;

import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.NetworkContextRepository;
import dev.subnetory.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextAccessServiceTest {

    @Mock UserRepository userRepository;
    @Mock NetworkContextRepository contextRepository;
    @Mock AuthAuditService auditService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonAdminReceivesOnlyExplicitContexts() {
        authenticate("operator", "ROLE_IP");
        when(userRepository.findAllowedContextIds("operator")).thenReturn(List.of(2L, 7L));

        ContextAccessService service = service();

        assertThat(service.allowedContextIds()).containsExactly(2L, 7L);
        service.requireAccess(7L);
    }

    @Test
    void readOnlyReceivesOnlyExplicitContexts() {
        authenticate("automation-ro", "ROLE_READ_ONLY");
        when(userRepository.findAllowedContextIds("automation-ro")).thenReturn(List.of(4L));

        ContextAccessService service = service();

        assertThat(service.allowedContextIds()).containsExactly(4L);
        service.requireAccess(4L);
        assertThatThrownBy(() -> service.requireAccess(8L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(auditService).recordContextAccessDenied("automation-ro", 8L);
    }

    @Test
    void adminReceivesAllContexts() {
        authenticate("admin", "ROLE_ADMIN");
        when(contextRepository.findAllIds()).thenReturn(List.of(1L, 2L, 3L));

        assertThat(service().allowedContextIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void forbiddenContextIsHiddenAndAudited() {
        authenticate("operator", "ROLE_NETWORK");
        when(userRepository.findAllowedContextIds("operator")).thenReturn(List.of(2L));

        assertThatThrownBy(() -> service().requireResourceAccess(9L, "Subnet", 44L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Subnet")
                .hasMessageContaining("44");
        verify(auditService).recordContextAccessDenied("operator", 9L);
    }

    private ContextAccessService service() {
        return new ContextAccessService(userRepository, contextRepository, auditService);
    }

    private void authenticate(String username, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, "n/a", List.of(new SimpleGrantedAuthority(authority))));
    }
}
