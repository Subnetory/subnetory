package dev.subnetory.web;

import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.MfaChallengeFilter;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.MfaLoginChallengeService;
import dev.subnetory.web.form.MfaConfirmForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Defi MFA Web au login : page dediee affichee par {@link MfaChallengeFilter}
 * tant que le second facteur n'a pas ete verifie pour la session courante.
 *
 * Sprint 2.37 / Lot 3. Meme rate limiting IP que le formulaire de login
 * ({@link LoginRateLimiter}, reutilise, aucun compteur separe).
 */
@Controller
@RequestMapping(MfaChallengeFilter.MFA_CHALLENGE_PATH)
public class MfaChallengeWebController {

    private final MfaLoginChallengeService mfaLoginChallengeService;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final AuthAuditService authAuditService;

    public MfaChallengeWebController(MfaLoginChallengeService mfaLoginChallengeService,
                                     LoginRateLimiter loginRateLimiter,
                                     ClientIpResolver clientIpResolver,
                                     AuthAuditService authAuditService) {
        this.mfaLoginChallengeService = mfaLoginChallengeService;
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.authAuditService = authAuditService;
    }

    @GetMapping
    public String challenge(Authentication authentication, HttpSession session, Model model) {
        if (!requiresChallenge(authentication, session)) {
            return "redirect:/";
        }

        model.addAttribute("form", new MfaConfirmForm());
        return "auth/login-mfa";
    }

    @PostMapping
    public String verify(@Valid @ModelAttribute("form") MfaConfirmForm form,
                         BindingResult bindingResult,
                         Authentication authentication,
                         HttpServletRequest request,
                         HttpSession session,
                         Model model) {
        if (!requiresChallenge(authentication, session)) {
            return "redirect:/";
        }

        String username = authentication.getName();
        String ipAddress = clientIpResolver.resolve(request);
        String userAgent = request.getHeader("User-Agent");

        if (loginRateLimiter.isLocked(ipAddress)) {
            return lockout(request);
        }

        boolean valid = !bindingResult.hasErrors()
                && mfaLoginChallengeService.verify(username, form.getCode());

        if (!valid) {
            LoginRateLimiter.RateLimitDecision decision = loginRateLimiter.recordFailure(ipAddress);

            if (decision.locked()) {
                authAuditService.recordLoginLocked(
                        username, ipAddress, userAgent,
                        "Trop de tentatives de code MFA. Compte temporairement bloque cote IP.");
                return lockout(request);
            }

            authAuditService.recordMfaChallengeFailed(username, ipAddress, userAgent);

            if (decision.delayed()) {
                applyDelay(decision);
            }

            model.addAttribute("flashError", "Code invalide. Reessayez.");
            model.addAttribute("form", form);
            return "auth/login-mfa";
        }

        loginRateLimiter.recordSuccess(ipAddress);
        session.setAttribute(MfaChallengeFilter.SESSION_MFA_VERIFIED, Boolean.TRUE);
        return "redirect:/";
    }

    private boolean requiresChallenge(Authentication authentication, HttpSession session) {
        return authentication != null
                && authentication.isAuthenticated()
                && mfaLoginChallengeService.isRequired(authentication.getName())
                && !Boolean.TRUE.equals(session.getAttribute(MfaChallengeFilter.SESSION_MFA_VERIFIED));
    }

    /**
     * IP verrouillee pendant le defi MFA : la session partiellement
     * authentifiee est invalidee pour forcer une reconnexion complete
     * (identifiants + MFA) une fois le verrou leve, plutot que de laisser
     * un attaquant retenter le defi MFA sans repasser par le mot de passe.
     */
    private String lockout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login?locked";
    }

    private void applyDelay(LoginRateLimiter.RateLimitDecision decision) {
        try {
            Thread.sleep(decision.waitDuration().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
