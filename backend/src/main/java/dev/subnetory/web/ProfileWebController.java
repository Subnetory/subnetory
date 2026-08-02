package dev.subnetory.web;

import dev.subnetory.domain.User;
import dev.subnetory.exception.InvalidMfaCodeException;
import dev.subnetory.exception.PasswordPolicyException;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.service.MfaService;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.web.form.MfaConfirmForm;
import dev.subnetory.web.form.MfaDisableForm;
import dev.subnetory.web.form.PasswordChangeForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Controller Web du profil utilisateur.
 *
 * Sprint 2.13 :
 * - affichage du profil ;
 * - changement de mot de passe self-service ;
 * - audit PASSWORD_CHANGE apres succes.
 */
@Controller
@RequestMapping("/profile")
public class ProfileWebController {

    private static final String SESSION_PENDING_MFA_SECRET = "pendingMfaSecret";

    private final UserAdminService userAdminService;
    private final ClientIpResolver clientIpResolver;
    private final MessageSource messageSource;

    public ProfileWebController(UserAdminService userAdminService,
                                ClientIpResolver clientIpResolver,
                                MessageSource messageSource) {
        this.userAdminService = userAdminService;
        this.clientIpResolver = clientIpResolver;
        this.messageSource = messageSource;
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    @GetMapping
    public String profile(Authentication authentication, Model model, Locale locale) {
        prepareProfileModel(authentication, model, new PasswordChangeForm(), locale);
        return "profile";
    }

    @GetMapping("/change-password-required")
    public String requiredPasswordChange(Authentication authentication, Model model, Locale locale) {
        User user = userAdminService.findByUsername(authentication.getName());
        if (!requiresPasswordChange(user)) {
            return "redirect:/profile";
        }

        prepareRequiredPasswordChangeModel(model, new PasswordChangeForm(), locale);
        return "auth/change-password-required";
    }

    @PostMapping("/change-password-required")
    public String requiredPasswordChange(
            @Valid @ModelAttribute("form") PasswordChangeForm form,
            BindingResult bindingResult,
            Authentication authentication,
            HttpServletRequest request,
            Model model,
            Locale locale) {
        User user = userAdminService.findByUsername(authentication.getName());
        if (!requiresPasswordChange(user)) {
            return "redirect:/profile";
        }

        validatePasswordConfirmation(form, bindingResult, locale);
        if (bindingResult.hasErrors()) {
            prepareRequiredPasswordChangeModel(model, form, locale);
            return "auth/change-password-required";
        }

        try {
            userAdminService.changeOwnPassword(
                    authentication.getName(),
                    form.getCurrentPassword(),
                    form.getNewPassword(),
                    clientIpResolver.resolve(request),
                    request.getHeader("User-Agent"));
            return "redirect:/";
        } catch (PasswordPolicyException e) {
            model.addAttribute("flashError", e.getMessage());
            prepareRequiredPasswordChangeModel(model, form, locale);
            return "auth/change-password-required";
        }
    }

    @PostMapping("/change-password")
    public String changePassword(@Valid @ModelAttribute("form") PasswordChangeForm form,
                                 BindingResult bindingResult,
                                 Authentication authentication,
                                 HttpServletRequest request,
                                 Model model,
                                 RedirectAttributes flash,
                                 Locale locale) {
        validatePasswordConfirmation(form, bindingResult, locale);

        if (bindingResult.hasErrors()) {
            prepareProfileModel(authentication, model, form, locale);
            return "profile";
        }

        try {
            String ipAddress = clientIpResolver.resolve(request);
            String userAgent = request.getHeader("User-Agent");

            userAdminService.changeOwnPassword(
                    authentication.getName(),
                    form.getCurrentPassword(),
                    form.getNewPassword(),
                    ipAddress,
                    userAgent);

            flash.addFlashAttribute("flashSuccess", msg("flash.profile.passwordUpdated", locale));
            return "redirect:/profile";
        } catch (PasswordPolicyException e) {
            model.addAttribute("flashError", e.getMessage());
            prepareProfileModel(authentication, model, form, locale);
            return "profile";
        }
    }

    @GetMapping("/mfa/setup")
    public String beginMfaSetup(Authentication authentication, HttpSession session, Model model, Locale locale) {
        User user = userAdminService.findByUsername(authentication.getName());
        if (user.isMfaEnabled()) {
            return "redirect:/profile";
        }

        MfaService.MfaSetup setup = userAdminService.beginMfaSetup(authentication.getName());
        session.setAttribute(SESSION_PENDING_MFA_SECRET, setup.secret());
        prepareMfaSetupModel(model, setup.secret(), setup.qrCodeDataUri(), new MfaConfirmForm(), locale);
        return "profile-mfa-setup";
    }

    @PostMapping("/mfa/setup")
    public String confirmMfaSetup(@Valid @ModelAttribute("form") MfaConfirmForm form,
                                  BindingResult bindingResult,
                                  Authentication authentication,
                                  HttpSession session,
                                  HttpServletRequest request,
                                  Model model,
                                  Locale locale) {
        String secret = (String) session.getAttribute(SESSION_PENDING_MFA_SECRET);
        if (secret == null) {
            return "redirect:/profile/mfa/setup";
        }

        if (bindingResult.hasErrors()) {
            prepareMfaSetupModel(model, secret, userAdminService.buildMfaQrCode(authentication.getName(), secret), form, locale);
            return "profile-mfa-setup";
        }

        try {
            List<String> recoveryCodes = userAdminService.enableMfa(
                    authentication.getName(),
                    secret,
                    form.getCode(),
                    clientIpResolver.resolve(request),
                    request.getHeader("User-Agent"));
            session.removeAttribute(SESSION_PENDING_MFA_SECRET);
            prepareMfaRecoveryCodesModel(model, recoveryCodes, msg("flash.profile.mfaEnabled", locale), locale);
            return "profile-mfa-recovery-codes";
        } catch (InvalidMfaCodeException e) {
            model.addAttribute("flashError", msg("flash.profile.mfaInvalidCode", locale));
            prepareMfaSetupModel(model, secret, userAdminService.buildMfaQrCode(authentication.getName(), secret), form, locale);
            return "profile-mfa-setup";
        }
    }

    @PostMapping("/mfa/disable")
    public String disableMfa(@Valid @ModelAttribute("mfaDisableForm") MfaDisableForm form,
                             BindingResult bindingResult,
                             Authentication authentication,
                             HttpServletRequest request,
                             RedirectAttributes flash,
                             Locale locale) {
        if (bindingResult.hasErrors()) {
            flash.addFlashAttribute("flashError", msg("flash.profile.mfaDisableRequiredFields", locale));
            return "redirect:/profile";
        }

        try {
            userAdminService.disableOwnMfa(
                    authentication.getName(),
                    form.getCurrentPassword(),
                    form.getCode(),
                    clientIpResolver.resolve(request),
                    request.getHeader("User-Agent"));
            flash.addFlashAttribute("flashSuccess", msg("flash.profile.mfaDisabled", locale));
        } catch (PasswordPolicyException | InvalidMfaCodeException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/profile";
    }

    @PostMapping("/mfa/recovery-codes/regenerate")
    public String regenerateMfaRecoveryCodes(@Valid @ModelAttribute("mfaRegenerateForm") MfaConfirmForm form,
                                             BindingResult bindingResult,
                                             Authentication authentication,
                                             HttpServletRequest request,
                                             Model model,
                                             RedirectAttributes flash,
                                             Locale locale) {
        if (bindingResult.hasErrors()) {
            flash.addFlashAttribute("flashError", msg("flash.profile.mfaCodeRequired", locale));
            return "redirect:/profile";
        }

        try {
            List<String> codes = userAdminService.regenerateOwnMfaRecoveryCodes(
                    authentication.getName(),
                    form.getCode(),
                    clientIpResolver.resolve(request),
                    request.getHeader("User-Agent"));
            prepareMfaRecoveryCodesModel(model, codes, msg("flash.profile.mfaCodesRegenerated", locale), locale);
            // Piege POST/redirect (audit du 31/07/2026) : cette vue est rendue
            // directement sur POST /profile/mfa/recovery-codes/regenerate, qui
            // n'a aucun @GetMapping jumeau. Sans cette surcharge, un
            // changement de contexte pendant que l'ecran des codes est
            // affiche declenche un GET 405 -> 500, et l'utilisateur perd son
            // seul affichage des nouveaux codes de recuperation.
            model.addAttribute("currentRequestPath", "/profile");
            return "profile-mfa-recovery-codes";
        } catch (PasswordPolicyException | InvalidMfaCodeException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/profile";
        }
    }

    private void prepareMfaSetupModel(Model model, String secret, String qrCodeDataUri, MfaConfirmForm form, Locale locale) {
        model.addAttribute("secret", secret);
        model.addAttribute("qrCodeDataUri", qrCodeDataUri);
        model.addAttribute("form", form);
        model.addAttribute("activeSection", "profile");
        model.addAttribute("pageTitle", msg("pageTitle.mfaSetup", locale));
    }

    private void prepareMfaRecoveryCodesModel(Model model, List<String> recoveryCodes, String message, Locale locale) {
        model.addAttribute("recoveryCodes", recoveryCodes);
        model.addAttribute("flashSuccess", message);
        model.addAttribute("activeSection", "profile");
        model.addAttribute("pageTitle", msg("pageTitle.mfaRecoveryCodes", locale));
    }

    private void validatePasswordConfirmation(
            PasswordChangeForm form,
            BindingResult bindingResult,
            Locale locale) {
        if (!bindingResult.hasErrors()
                && !form.getNewPassword().equals(form.getConfirmPassword())) {
            bindingResult.rejectValue(
                    "confirmPassword",
                    "password.confirmation.mismatch",
                    msg("validation.password.confirmationMismatch", locale));
        }
    }

    private boolean requiresPasswordChange(User user) {
        return user.isMustChangePassword()
                && !"LDAP".equalsIgnoreCase(user.getAuthType());
    }

    private void prepareRequiredPasswordChangeModel(
            Model model,
            PasswordChangeForm form,
            Locale locale) {
        model.addAttribute("form", form);
        model.addAttribute(
                "pageTitle",
                msg("pageTitle.passwordChangeRequired", locale));
    }

    private void prepareProfileModel(Authentication authentication,
                                     Model model,
                                     PasswordChangeForm form,
                                     Locale locale) {
        model.addAttribute("user", userAdminService.findByUsername(authentication.getName()));
        model.addAttribute("form", form);
        model.addAttribute("mfaDisableForm", new MfaDisableForm());
        model.addAttribute("mfaRegenerateForm", new MfaConfirmForm());
        model.addAttribute("activeSection", "profile");
        model.addAttribute("pageTitle", msg("pageTitle.profile", locale));
    }
}