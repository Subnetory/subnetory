package dev.subnetory.api.v1;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.dto.AdminPasswordResetRequest;
import dev.subnetory.dto.AdminRoleResponse;
import dev.subnetory.dto.AdminUserContextsRequest;
import dev.subnetory.dto.AdminUserCreateRequest;
import dev.subnetory.dto.AdminUserResponse;
import dev.subnetory.dto.AdminUserRolesRequest;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.service.UserTokenInvalidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Users", description = "Administration des comptes, rôles, contextes et jetons API")
public class AdminUserController {

    private final UserAdminService userAdminService;
    private final UserTokenInvalidationService userTokenInvalidationService;
    private final AuthAuditService authAuditService;
    private final ClientIpResolver clientIpResolver;

    public AdminUserController(UserAdminService userAdminService,
                               UserTokenInvalidationService userTokenInvalidationService,
                               AuthAuditService authAuditService,
                               ClientIpResolver clientIpResolver) {
        this.userAdminService = userAdminService;
        this.userTokenInvalidationService = userTokenInvalidationService;
        this.authAuditService = authAuditService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    @Operation(summary = "Lister les utilisateurs")
    public Page<AdminUserResponse> list(@PageableDefault(size = 25, sort = "username") Pageable pageable) {
        return userAdminService.findAll(pageable).map(this::toResponse);
    }

    @GetMapping("/assignable-roles")
    @Operation(summary = "Lister les rôles attribuables")
    public List<AdminRoleResponse> assignableRoles() {
        return userAdminService.findAssignableRoles().stream()
                .map(role -> new AdminRoleResponse(
                        role.getId(),
                        role.getName(),
                        role.getName().replace("ROLE_", ""),
                        roleDescription(role.getName())))
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un utilisateur")
    public AdminUserResponse detail(@PathVariable Long id) {
        return toResponse(userAdminService.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un compte local")
    public AdminUserResponse create(@RequestBody AdminUserCreateRequest request,
                                    Authentication authentication) {
        return toResponse(userAdminService.createLocalUser(
                request.username(),
                request.email(),
                request.temporaryPassword(),
                request.enabled(),
                request.roleIds(),
                request.contextIds(),
                authentication.getName()));
    }

    @PatchMapping("/{id}/roles")
    @Operation(summary = "Modifier les rôles d'un utilisateur")
    public AdminUserResponse updateRoles(@PathVariable Long id,
                                         @RequestBody AdminUserRolesRequest request,
                                         Authentication authentication) {
        return toResponse(userAdminService.updateRoles(id, request.roleIds(), authentication.getName()));
    }

    @PatchMapping("/{id}/contexts")
    @Operation(summary = "Modifier les contextes autorisés d'un utilisateur")
    public AdminUserResponse updateContexts(@PathVariable Long id,
                                            @RequestBody AdminUserContextsRequest request,
                                            Authentication authentication) {
        return toResponse(userAdminService.updateContexts(id, request.contextIds(), authentication.getName()));
    }

    @PatchMapping("/{id}/enable")
    @Operation(summary = "Activer un utilisateur")
    public AdminUserResponse enable(@PathVariable Long id, Authentication authentication) {
        return toResponse(userAdminService.setEnabled(id, true, authentication.getName()));
    }

    @PatchMapping("/{id}/disable")
    @Operation(summary = "Désactiver un utilisateur")
    public AdminUserResponse disable(@PathVariable Long id, Authentication authentication) {
        return toResponse(userAdminService.setEnabled(id, false, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer definitivement un compte utilisateur",
            description = "Suppression physique et irreversible. Refusee pour son propre compte "
                    + "ou pour le dernier administrateur actif. Les roles, acces contextes et "
                    + "codes de recuperation MFA du compte sont supprimes en cascade ; les entrees "
                    + "d'audit et de sauvegarde existantes qui le mentionnent sont conservees.")
    public void delete(@PathVariable Long id,
                       Authentication authentication,
                       HttpServletRequest httpRequest) {
        userAdminService.deleteUser(
                id,
                authentication.getName(),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Réinitialiser le mot de passe d'un compte local")
    public void resetPassword(@PathVariable Long id,
                              @RequestBody AdminPasswordResetRequest request,
                              Authentication authentication,
                              HttpServletRequest httpRequest) {
        userAdminService.adminResetPassword(
                id,
                request.newPassword(),
                authentication.getName(),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/{id}/disable-mfa")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Désactiver le MFA d'un compte (anti-lockout admin)",
            description = "Action réservée aux administrateurs : désactive le MFA d'un compte "
                    + "cible sans exiger ni mot de passe ni code MFA de ce compte. Destinée au "
                    + "cas où l'appareil TOTP et les codes de récupération sont perdus.")
    public void disableMfa(@PathVariable Long id,
                           Authentication authentication,
                           HttpServletRequest httpRequest) {
        userAdminService.adminDisableMfa(
                id,
                authentication.getName(),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/{id}/invalidate-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Invalider tous les jetons API d'un utilisateur")
    public void invalidateTokens(@PathVariable Long id,
                                 Authentication authentication,
                                 HttpServletRequest request) {
        User user = userAdminService.findById(id);
        String reason = UserTokenInvalidationService.REASON_ADMIN_REVOKE;
        userTokenInvalidationService.invalidateTokens(user.getUsername(), authentication.getName(), reason);
        authAuditService.recordTokensInvalidated(
                authentication.getName(),
                user.getUsername(),
                clientIpResolver.resolve(request),
                request.getHeader("User-Agent"),
                reason);
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

    private String roleDescription(String roleName) {
        return switch (roleName) {
            case "ROLE_ADMIN" -> "Administration complète.";
            case "ROLE_READ_ONLY" -> "Lecture seule sur les contextes autorisés.";
            case "ROLE_NETWORK" -> "Création et modification des contextes, sites, VLAN, sous-réseaux et scans.";
            case "ROLE_IP" -> "Création, modification, suppression, import et export des adresses IP.";
            default -> "Rôle non attribuable.";
        };
    }
}
