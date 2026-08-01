package dev.subnetory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.domain.User;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.web.form.PasswordChangeForm;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;

@ExtendWith(MockitoExtension.class)
class ProfileWebControllerMandatoryPasswordChangeTest {

    @Mock UserAdminService userAdminService;
    @Mock ClientIpResolver clientIpResolver;

    ProfileWebController controller;
    Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new ProfileWebController(userAdminService, clientIpResolver);
        authentication = new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of());
    }

    @Test
    void requiredChangePage_displaysDedicatedTemplate() {
        when(userAdminService.findByUsername("admin"))
                .thenReturn(buildUser(true));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.requiredPasswordChange(authentication, model);

        assertThat(view).isEqualTo("auth/change-password-required");
        assertThat(model.get("form")).isInstanceOf(PasswordChangeForm.class);
    }

    @Test
    void requiredChangePage_redirectsWhenRequirementAlreadyCleared() {
        when(userAdminService.findByUsername("admin"))
                .thenReturn(buildUser(false));

        String view = controller.requiredPasswordChange(
                authentication, new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/profile");
    }

    @Test
    void validMandatoryChange_updatesPasswordAndRedirectsDashboard() {
        User user = buildUser(true);
        PasswordChangeForm form = form(
                "TemporaryPass123!",
                "NewSecurePass456!",
                "NewSecurePass456!");
        BeanPropertyBindingResult binding =
                new BeanPropertyBindingResult(form, "form");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "mandatory-change-test");

        when(userAdminService.findByUsername("admin")).thenReturn(user);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        String view = controller.requiredPasswordChange(
                form,
                binding,
                authentication,
                request,
                new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/");
        verify(userAdminService).changeOwnPassword(
                "admin",
                "TemporaryPass123!",
                "NewSecurePass456!",
                "127.0.0.1",
                "mandatory-change-test");
    }

    @Test
    void mismatchedConfirmation_keepsMandatoryPage() {
        User user = buildUser(true);
        PasswordChangeForm form = form(
                "TemporaryPass123!",
                "NewSecurePass456!",
                "DifferentPass789!");
        BeanPropertyBindingResult binding =
                new BeanPropertyBindingResult(form, "form");

        when(userAdminService.findByUsername("admin")).thenReturn(user);

        String view = controller.requiredPasswordChange(
                form,
                binding,
                authentication,
                new MockHttpServletRequest(),
                new ExtendedModelMap());

        assertThat(view).isEqualTo("auth/change-password-required");
        assertThat(binding.hasFieldErrors("confirmPassword")).isTrue();
        verify(userAdminService, never()).changeOwnPassword(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private User buildUser(boolean required) {
        User user = new User();
        user.setUsername("admin");
        user.setAuthType("LOCAL");
        user.setEnabled(true);
        user.setMustChangePassword(required);
        return user;
    }

    private PasswordChangeForm form(
            String currentPassword,
            String newPassword,
            String confirmation) {
        PasswordChangeForm form = new PasswordChangeForm();
        form.setCurrentPassword(currentPassword);
        form.setNewPassword(newPassword);
        form.setConfirmPassword(confirmation);
        return form;
    }
}
