package dev.subnetory.api.v1;

import dev.subnetory.dto.MfaChallengeRequest;
import dev.subnetory.dto.MfaDisableRequest;
import dev.subnetory.dto.MfaEnableRequest;
import dev.subnetory.dto.MfaRecoveryCodesResponse;
import dev.subnetory.dto.MfaSetupResponse;
import dev.subnetory.dto.PasswordChangeRequest;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.service.MfaService;
import dev.subnetory.service.UserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileControllerTest {

    private final UserAdminService userAdminService = mock(UserAdminService.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final ProfileController controller = new ProfileController(userAdminService, clientIpResolver);

    @Test
    void changePasswordRejectsConfirmationMismatch() {
        assertThatThrownBy(() -> controller.changePassword(
                new PasswordChangeRequest("OldPass123!", "NewPass123!", "Mismatch123!"),
                new UsernamePasswordAuthenticationToken("operator", "n/a"),
                mock(HttpServletRequest.class)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void changePasswordDelegatesToUserAdminService() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");

        controller.changePassword(
                new PasswordChangeRequest("OldPass123!", "NewPass123!", "NewPass123!"),
                new UsernamePasswordAuthenticationToken("operator", "n/a"),
                request);

        verify(userAdminService).changeOwnPassword(
                "operator",
                "OldPass123!",
                "NewPass123!",
                "127.0.0.1",
                "JUnit");
    }

    @Test
    void beginMfaSetupDelegatesAndMapsResponse() {
        when(userAdminService.beginMfaSetup("operator"))
                .thenReturn(new MfaService.MfaSetup("SECRET123", "data:image/png;base64,xyz"));

        MfaSetupResponse response = controller.beginMfaSetup(
                new UsernamePasswordAuthenticationToken("operator", "n/a"));

        assertThat(response.secret()).isEqualTo("SECRET123");
        assertThat(response.qrCodeDataUri()).isEqualTo("data:image/png;base64,xyz");
    }

    @Test
    void enableMfaDelegatesAndReturnsRecoveryCodes() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");
        when(userAdminService.enableMfa("operator", "SECRET123", "123456", "127.0.0.1", "JUnit"))
                .thenReturn(List.of("a1-b2", "c3-d4"));

        MfaRecoveryCodesResponse response = controller.enableMfa(
                new MfaEnableRequest("SECRET123", "123456"),
                new UsernamePasswordAuthenticationToken("operator", "n/a"),
                request);

        assertThat(response.recoveryCodes()).containsExactly("a1-b2", "c3-d4");
    }

    @Test
    void disableMfaDelegatesToUserAdminService() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");

        controller.disableMfa(
                new MfaDisableRequest("CurrentPass123!", "123456"),
                new UsernamePasswordAuthenticationToken("operator", "n/a"),
                request);

        verify(userAdminService).disableOwnMfa(
                "operator", "CurrentPass123!", "123456", "127.0.0.1", "JUnit");
    }

    @Test
    void regenerateRecoveryCodesDelegatesAndReturnsCodes() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");
        when(userAdminService.regenerateOwnMfaRecoveryCodes("operator", "123456", "127.0.0.1", "JUnit"))
                .thenReturn(List.of("e5-f6"));

        MfaRecoveryCodesResponse response = controller.regenerateRecoveryCodes(
                new MfaChallengeRequest("123456"),
                new UsernamePasswordAuthenticationToken("operator", "n/a"),
                request);

        assertThat(response.recoveryCodes()).containsExactly("e5-f6");
    }
}
