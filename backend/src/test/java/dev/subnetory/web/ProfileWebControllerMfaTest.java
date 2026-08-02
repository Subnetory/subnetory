package dev.subnetory.web;

import dev.subnetory.domain.User;
import dev.subnetory.exception.InvalidMfaCodeException;
import dev.subnetory.exception.PasswordPolicyException;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.service.MfaService;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.web.form.MfaConfirmForm;
import dev.subnetory.web.form.MfaDisableForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileWebControllerMfaTest {

    private static final Locale LOCALE = Locale.FRENCH;

    @Mock UserAdminService userAdminService;
    @Mock ClientIpResolver clientIpResolver;

    ProfileWebController controller;
    Authentication authentication;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        controller = new ProfileWebController(userAdminService, clientIpResolver, messageSource);
        authentication = new UsernamePasswordAuthenticationToken("operator", "n/a", List.of());
    }

    private User buildUser(boolean mfaEnabled) {
        User user = new User();
        user.setUsername("operator");
        user.setAuthType("LOCAL");
        user.setMfaEnabled(mfaEnabled);
        return user;
    }

    @Test
    void beginSetup_alreadyEnabled_redirectsToProfile() {
        when(userAdminService.findByUsername("operator")).thenReturn(buildUser(true));

        String view = controller.beginMfaSetup(authentication, new MockHttpSession(), new ExtendedModelMap(), LOCALE);

        assertThat(view).isEqualTo("redirect:/profile");
    }

    @Test
    void beginSetup_notEnabled_storesSecretInSessionAndShowsForm() {
        when(userAdminService.findByUsername("operator")).thenReturn(buildUser(false));
        when(userAdminService.beginMfaSetup("operator"))
                .thenReturn(new MfaService.MfaSetup("SECRET123", "data:image/png;base64,xyz"));
        MockHttpSession session = new MockHttpSession();
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.beginMfaSetup(authentication, session, model, LOCALE);

        assertThat(view).isEqualTo("profile-mfa-setup");
        assertThat(session.getAttribute("pendingMfaSecret")).isEqualTo("SECRET123");
        assertThat(model.get("qrCodeDataUri")).isEqualTo("data:image/png;base64,xyz");
        assertThat(model.get("form")).isInstanceOf(MfaConfirmForm.class);
    }

    @Test
    void confirmSetup_noSessionSecret_redirectsToSetupPage() {
        MockHttpSession session = new MockHttpSession();
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("123456");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");

        String view = controller.confirmMfaSetup(
                form, binding, authentication, session, new MockHttpServletRequest(), new ExtendedModelMap(), LOCALE);

        assertThat(view).isEqualTo("redirect:/profile/mfa/setup");
    }

    @Test
    void confirmSetup_validCode_activatesAndShowsRecoveryCodes() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("pendingMfaSecret", "SECRET123");
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("123456");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(userAdminService.enableMfa("operator", "SECRET123", "123456", "127.0.0.1", null))
                .thenReturn(List.of("a1-b2", "c3-d4"));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.confirmMfaSetup(form, binding, authentication, session, request, model, LOCALE);

        assertThat(view).isEqualTo("profile-mfa-recovery-codes");
        assertThat(session.getAttribute("pendingMfaSecret")).isNull();
        assertThat(model.get("recoveryCodes")).isEqualTo(List.of("a1-b2", "c3-d4"));
    }

    @Test
    void confirmSetup_invalidCode_redisplaysSetupFormWithError() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("pendingMfaSecret", "SECRET123");
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("000000");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(userAdminService.enableMfa("operator", "SECRET123", "000000", "127.0.0.1", null))
                .thenThrow(new InvalidMfaCodeException());
        when(userAdminService.buildMfaQrCode("operator", "SECRET123"))
                .thenReturn("data:image/png;base64,xyz");
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.confirmMfaSetup(form, binding, authentication, session, request, model, LOCALE);

        assertThat(view).isEqualTo("profile-mfa-setup");
        assertThat(session.getAttribute("pendingMfaSecret")).isEqualTo("SECRET123");
        assertThat(model.get("flashError")).isNotNull();
    }

    @Test
    void disableMfa_validationErrors_redirectsWithFlashError() {
        MfaDisableForm form = new MfaDisableForm();
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "mfaDisableForm");
        binding.reject("required");
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.disableMfa(form, binding, authentication, new MockHttpServletRequest(), flash, LOCALE);

        assertThat(view).isEqualTo("redirect:/profile");
        assertThat(flash.getFlashAttributes().get("flashError")).isNotNull();
        verify(userAdminService, never()).disableOwnMfa(any(), any(), any(), any(), any());
    }

    @Test
    void disableMfa_success_redirectsWithFlashSuccess() {
        MfaDisableForm form = new MfaDisableForm();
        form.setCurrentPassword("CurrentPass123!");
        form.setCode("123456");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "mfaDisableForm");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.disableMfa(form, binding, authentication, request, flash, LOCALE);

        assertThat(view).isEqualTo("redirect:/profile");
        assertThat(flash.getFlashAttributes().get("flashSuccess")).isNotNull();
        verify(userAdminService).disableOwnMfa("operator", "CurrentPass123!", "123456", "127.0.0.1", null);
    }

    @Test
    void disableMfa_serviceRejects_redirectsWithFlashError() {
        MfaDisableForm form = new MfaDisableForm();
        form.setCurrentPassword("WrongPass!");
        form.setCode("123456");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "mfaDisableForm");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        org.mockito.Mockito.doThrow(new PasswordPolicyException("Le mot de passe actuel est incorrect."))
                .when(userAdminService).disableOwnMfa("operator", "WrongPass!", "123456", "127.0.0.1", null);
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.disableMfa(form, binding, authentication, request, flash, LOCALE);

        assertThat(view).isEqualTo("redirect:/profile");
        assertThat(flash.getFlashAttributes().get("flashError")).isEqualTo("Le mot de passe actuel est incorrect.");
    }

    @Test
    void regenerateRecoveryCodes_success_showsRecoveryCodesPage() {
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("123456");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "mfaRegenerateForm");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(userAdminService.regenerateOwnMfaRecoveryCodes("operator", "123456", "127.0.0.1", null))
                .thenReturn(List.of("e5-f6"));
        ExtendedModelMap model = new ExtendedModelMap();
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.regenerateMfaRecoveryCodes(form, binding, authentication, request, model, flash, LOCALE);

        assertThat(view).isEqualTo("profile-mfa-recovery-codes");
        assertThat(model.get("recoveryCodes")).isEqualTo(List.of("e5-f6"));
    }
}
