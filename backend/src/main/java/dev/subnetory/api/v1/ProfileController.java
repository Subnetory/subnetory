package dev.subnetory.api.v1;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.dto.AdminUserResponse;
import dev.subnetory.dto.MfaChallengeRequest;
import dev.subnetory.dto.MfaDisableRequest;
import dev.subnetory.dto.MfaEnableRequest;
import dev.subnetory.dto.MfaRecoveryCodesResponse;
import dev.subnetory.dto.MfaSetupResponse;
import dev.subnetory.dto.PasswordChangeRequest;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.service.MfaService;
import dev.subnetory.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;

@RestController
@RequestMapping("/api/v1/profile")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Profile", description = "Profil du compte authentifié")
public class ProfileController {

    private final UserAdminService userAdminService;
    private final ClientIpResolver clientIpResolver;

    public ProfileController(UserAdminService userAdminService,
                             ClientIpResolver clientIpResolver) {
        this.userAdminService = userAdminService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    @Operation(summary = "Consulter le profil du compte authentifié")
    public AdminUserResponse profile(Authentication authentication) {
        return toResponse(userAdminService.findByUsername(authentication.getName()));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Changer le mot de passe du compte authentifié")
    public void changePassword(@RequestBody PasswordChangeRequest request,
                               Authentication authentication,
                               HttpServletRequest httpRequest) {
        if (request.confirmPassword() == null || !request.confirmPassword().equals(request.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La confirmation ne correspond pas au nouveau mot de passe.");
        }
        userAdminService.changeOwnPassword(
                authentication.getName(),
                request.currentPassword(),
                request.newPassword(),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/mfa/setup")
    @Operation(summary = "Demarrer un enrolement MFA (TOTP)",
            description = "Genere un nouveau secret et le QR code correspondant. Rien n'est "
                    + "active tant que POST /api/v1/profile/mfa/enable n'a pas confirme un "
                    + "premier code valide.")
    public MfaSetupResponse beginMfaSetup(Authentication authentication) {
        MfaService.MfaSetup setup = userAdminService.beginMfaSetup(authentication.getName());
        return new MfaSetupResponse(setup.secret(), setup.qrCodeDataUri());
    }

    @PostMapping("/mfa/enable")
    @Operation(summary = "Confirmer et activer le MFA",
            description = "Exige un premier code TOTP valide sur le secret propose par "
                    + "POST /api/v1/profile/mfa/setup. Retourne 10 codes de recuperation, "
                    + "affiches une seule fois.")
    public MfaRecoveryCodesResponse enableMfa(@Valid @RequestBody MfaEnableRequest request,
                                              Authentication authentication,
                                              HttpServletRequest httpRequest) {
        var codes = userAdminService.enableMfa(
                authentication.getName(),
                request.secret(),
                request.code(),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return new MfaRecoveryCodesResponse(codes);
    }

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactiver le MFA",
            description = "Exige le mot de passe courant et un code MFA valide (TOTP ou "
                    + "recuperation). Supprime le secret et tous les codes de recuperation.")
    public void disableMfa(@Valid @RequestBody MfaDisableRequest request,
                           Authentication authentication,
                           HttpServletRequest httpRequest) {
        userAdminService.disableOwnMfa(
                authentication.getName(),
                request.currentPassword(),
                request.code(),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/mfa/recovery-codes/regenerate")
    @Operation(summary = "Regenerer les codes de recuperation MFA",
            description = "Exige un code MFA valide (TOTP ou recuperation). Invalide les 10 "
                    + "anciens codes et en genere 10 nouveaux, affiches une seule fois.")
    public MfaRecoveryCodesResponse regenerateRecoveryCodes(@Valid @RequestBody MfaChallengeRequest request,
                                                            Authentication authentication,
                                                            HttpServletRequest httpRequest) {
        var codes = userAdminService.regenerateOwnMfaRecoveryCodes(
                authentication.getName(),
                request.code(),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return new MfaRecoveryCodesResponse(codes);
    }

    private AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getAuthType(),
                user.isEnabled(),
                user.isMustChangePassword(),
                user.isMfaEnabled(),
                user.getRoles().stream()
                        .map(Role::getName)
                        .sorted()
                        .toList(),
                user.getAllowedContexts().stream()
                        .sorted(Comparator.comparing(NetworkContext::getName))
                        .map(context -> new AdminUserResponse.ContextRef(context.getId(), context.getName()))
                        .toList(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
