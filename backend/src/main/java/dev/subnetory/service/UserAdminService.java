package dev.subnetory.service;

import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.domain.NetworkContext;
import dev.subnetory.exception.InvalidMfaCodeException;
import dev.subnetory.exception.PasswordPolicyException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.RoleRepository;
import dev.subnetory.repository.UserRepository;
import dev.subnetory.repository.NetworkContextRepository;
import dev.subnetory.security.AssignableRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Service d'administration des utilisateurs.
 *
 * Toutes les regles metier utilisateurs sont ici, pas dans les controllers.
 */
@Service
@Transactional
public class UserAdminService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String AUTH_TYPE_LDAP = "LDAP";
    private static final String AUTH_TYPE_LOCAL = "LOCAL";
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{3,100}$");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordEncoder passwordEncoder;
    private final AuthAuditService authAuditService;
    private final UserTokenInvalidationService userTokenInvalidationService;
    private final NetworkContextRepository contextRepository;
    private final MfaService mfaService;

    @Autowired
    public UserAdminService(UserRepository userRepository,
                            RoleRepository roleRepository,
                            PasswordPolicyService passwordPolicyService,
                            PasswordEncoder passwordEncoder,
                            AuthAuditService authAuditService,
                            UserTokenInvalidationService userTokenInvalidationService,
                            NetworkContextRepository contextRepository,
                            MfaService mfaService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordEncoder = passwordEncoder;
        this.authAuditService = authAuditService;
        this.userTokenInvalidationService = userTokenInvalidationService;
        this.contextRepository = contextRepository;
        this.mfaService = mfaService;
    }

    // Constructeur conserve pour les tests existants (invalidation de tokens + contexte).
    UserAdminService(UserRepository userRepository,
                     RoleRepository roleRepository,
                     PasswordPolicyService passwordPolicyService,
                     PasswordEncoder passwordEncoder,
                     AuthAuditService authAuditService,
                     UserTokenInvalidationService userTokenInvalidationService,
                     NetworkContextRepository contextRepository) {
        this(userRepository, roleRepository, passwordPolicyService, passwordEncoder,
                authAuditService, userTokenInvalidationService, contextRepository, null);
    }

    // Constructeur conserve pour les tests de securite mot de passe existants.
    UserAdminService(UserRepository userRepository,
                     RoleRepository roleRepository,
                     PasswordPolicyService passwordPolicyService,
                     PasswordEncoder passwordEncoder,
                     AuthAuditService authAuditService,
                     UserTokenInvalidationService userTokenInvalidationService) {
        this(userRepository, roleRepository, passwordPolicyService, passwordEncoder,
                authAuditService, userTokenInvalidationService, null, null);
    }

    // Constructeur package-private conserve pour les tests unitaires existants.
    UserAdminService(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordPolicyService = null;
        this.passwordEncoder = null;
        this.authAuditService = null;
        this.userTokenInvalidationService = null;
        this.contextRepository = null;
        this.mfaService = null;
    }

    // Lecture

    @Transactional(readOnly = true)
    public Page<User> findAll(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
    }

    @Transactional(readOnly = true)
    public List<Role> findAllRoles() {
        return roleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Role> findAssignableRoles() {
        return AssignableRoles.filter(roleRepository.findAll());
    }

    @Transactional(readOnly = true)
    public List<NetworkContext> findAllContexts() {
        if (contextRepository == null) return List.of();
        return contextRepository.findAll(org.springframework.data.domain.Sort.by("name"));
    }

    // Creation de compte local

    public User createLocalUser(String username,
                                String email,
                                String temporaryPassword,
                                boolean enabled,
                                Set<Long> roleIds,
                                Set<Long> contextIds,
                                String currentUsername) {
        String normalizedUsername = normalizeUsername(username);

        if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw new IllegalArgumentException(
                    "L'identifiant doit contenir 3 a 100 caracteres : lettres, chiffres, point, tiret ou underscore.");
        }
        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new IllegalArgumentException("Un compte existe deja avec cet identifiant.");
        }
        if (roleIds == null || roleIds.isEmpty()) {
            throw new AdminLockoutException("Un utilisateur doit avoir au moins un role.");
        }

        requirePasswordPolicyService().validate(temporaryPassword);

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setEmail(normalizeEmail(email));
        user.setPassword(requirePasswordEncoder().encode(temporaryPassword));
        user.setAuthType(AUTH_TYPE_LOCAL);
        user.setEnabled(enabled);
        user.setMustChangePassword(true);
        user.setRoles(resolveRoles(roleIds));
        user.setAllowedContexts(resolveContexts(contextIds));

        User saved = userRepository.save(user);
        if (authAuditService != null) {
            authAuditService.recordUserCreated(
                    currentUsername,
                    saved.getUsername(),
                    saved.getRoles().size(),
                    saved.getAllowedContexts().size());
        }
        return saved;
    }

    // Changement de mot de passe self-service

    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        changeOwnPassword(username, currentPassword, newPassword, null, null);
    }

    /**
     * Change le mot de passe de l'utilisateur connecte.
     *
     * Regles :
     * - compte LDAP refuse ;
     * - ancien mot de passe obligatoire et valide ;
     * - nouveau mot de passe conforme a PasswordPolicyService ;
     * - nouveau mot de passe different de l'ancien ;
     * - hash BCrypt avant sauvegarde ;
     * - audit PASSWORD_CHANGE apres succes.
     *
     * @param username        utilisateur connecte
     * @param currentPassword mot de passe actuel en clair
     * @param newPassword     nouveau mot de passe en clair
     * @param ipAddress       adresse IP cliente
     * @param userAgent       user-agent HTTP
     */
    public void changeOwnPassword(String username,
                                  String currentPassword,
                                  String newPassword,
                                  String ipAddress,
                                  String userAgent) {
        User user = findByUsername(username);

        if (AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType())) {
            throw new PasswordPolicyException(
                    "Ce compte est gere par LDAP. Le mot de passe doit etre change depuis votre annuaire d'entreprise.");
        }

        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new PasswordPolicyException(
                    "Ce compte ne possede pas de mot de passe local modifiable.");
        }

        if (currentPassword == null || currentPassword.isBlank()) {
            throw new PasswordPolicyException("Le mot de passe actuel est obligatoire.");
        }

        if (!requirePasswordEncoder().matches(currentPassword, user.getPassword())) {
            throw new PasswordPolicyException("Le mot de passe actuel est incorrect.");
        }

        requirePasswordPolicyService().validate(newPassword);

        if (requirePasswordEncoder().matches(newPassword, user.getPassword())) {
            throw new PasswordPolicyException(
                    "Le nouveau mot de passe doit etre different de l'ancien.");
        }

        user.setPassword(requirePasswordEncoder().encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        if (userTokenInvalidationService != null) {
            String reason = UserTokenInvalidationService.REASON_PASSWORD_CHANGE;
            userTokenInvalidationService.invalidateTokens(username, username, reason);

            if (authAuditService != null) {
                authAuditService.recordTokensInvalidated(username, username, ipAddress, userAgent, reason);
            }
        }

        if (authAuditService != null) {
            authAuditService.recordPasswordChange(username, ipAddress, userAgent);
        }
    }

    // Reinitialisation de mot de passe par administrateur

    public void adminResetPassword(Long userId, String newPassword) {
        adminResetPassword(userId, newPassword, null, null, null);
    }

    /**
     * Reinitialise le mot de passe d'un compte local.
     *
     * Regles :
     * - compte LDAP refuse ;
     * - politique de mot de passe appliquee ;
     * - hash BCrypt avant sauvegarde ;
     * - audit ADMIN_PASSWORD_RESET apres succes.
     *
     * @param userId        ID de l'utilisateur cible
     * @param newPassword   nouveau mot de passe en clair
     * @param adminUsername administrateur ayant realise l'action
     * @param ipAddress     adresse IP cliente
     * @param userAgent     user-agent HTTP
     */
    public void adminResetPassword(Long userId,
                                   String newPassword,
                                   String adminUsername,
                                   String ipAddress,
                                   String userAgent) {
        User user = findById(userId);

        if (AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType())) {
            throw new PasswordPolicyException(
                    "Ce compte est gere par LDAP. Le mot de passe doit etre change depuis votre annuaire d'entreprise.");
        }

        requirePasswordPolicyService().validate(newPassword);

        user.setPassword(requirePasswordEncoder().encode(newPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);

        if (userTokenInvalidationService != null) {
            String reason = UserTokenInvalidationService.REASON_PASSWORD_CHANGE;
            userTokenInvalidationService.invalidateTokens(user.getUsername(), adminUsername, reason);

            if (authAuditService != null) {
                authAuditService.recordTokensInvalidated(adminUsername, user.getUsername(), ipAddress, userAgent, reason);
            }
        }

        if (authAuditService != null) {
            authAuditService.recordAdminPasswordReset(adminUsername, user.getUsername(), ipAddress, userAgent);
        }
    }

    // Mise a jour des roles

    /**
     * Met a jour les roles d'un utilisateur.
     *
     * Regles anti-lockout verifiees :
     * - au moins un role obligatoire ;
     * - impossible de retirer ROLE_ADMIN au dernier ADMIN actif.
     */
    public User updateRoles(Long targetId, Set<Long> newRoleIds, String currentUsername) {
        User target = findById(targetId);

        if (newRoleIds == null || newRoleIds.isEmpty()) {
            throw new AdminLockoutException("Un utilisateur doit avoir au moins un role.");
        }

        Set<Role> resolvedRoles = resolveRoles(newRoleIds);

        boolean targetIsAdmin = hasRole(target, ROLE_ADMIN);
        boolean newRolesIncludeAdmin = resolvedRoles.stream()
                .anyMatch(r -> ROLE_ADMIN.equals(r.getName()));

        if (targetIsAdmin && !newRolesIncludeAdmin) {
            long activeAdminCount = userRepository.countActiveByRoleName(ROLE_ADMIN);
            if (activeAdminCount <= 1) {
                throw new AdminLockoutException(
                        "Impossible de retirer le role ADMIN au dernier administrateur actif.");
            }
        }

        target.setRoles(resolvedRoles);
        User saved = userRepository.save(target);
        invalidateAuthorizationTokens(saved.getUsername(), currentUsername);

        // Audit manquant (02/08/2026, correctif MOYENNE) : contrairement a
        // updateContexts() ci-dessus, cette modification de privileges
        // (y compris attribution/retrait de ROLE_ADMIN) n'etait jusqu'ici
        // pas tracee dans le journal d'audit.
        if (authAuditService != null) {
            authAuditService.recordUserRolesUpdated(
                    currentUsername, saved.getUsername(), resolvedRoles.size());
        }
        return saved;
    }

    /** Met a jour le perimetre de contextes d'un utilisateur. */
    public User updateContexts(Long targetId,
                               Set<Long> newContextIds,
                               String currentUsername) {
        User target = findById(targetId);
        Set<NetworkContext> resolvedContexts = new HashSet<>();

        resolvedContexts.addAll(resolveContexts(newContextIds));

        target.setAllowedContexts(resolvedContexts);
        User saved = userRepository.save(target);
        invalidateAuthorizationTokens(saved.getUsername(), currentUsername);

        if (authAuditService != null) {
            authAuditService.recordUserContextsUpdated(
                    currentUsername, saved.getUsername(), resolvedContexts.size());
        }
        return saved;
    }

    // Activation / desactivation

    /**
     * Active ou desactive un compte utilisateur.
     */
    public User setEnabled(Long targetId, boolean enabled, String currentUsername) {
        User target = findById(targetId);

        if (!enabled) {
            User current = findByUsername(currentUsername);
            if (target.getId().equals(current.getId())) {
                throw new AdminLockoutException("Impossible de desactiver votre propre compte.");
            }

            if (hasRole(target, ROLE_ADMIN)) {
                long activeAdminCount = userRepository.countActiveByRoleName(ROLE_ADMIN);
                if (activeAdminCount <= 1) {
                    throw new AdminLockoutException(
                            "Impossible de desactiver le dernier administrateur actif.");
                }
            }
        }

        target.setEnabled(enabled);
        User saved = userRepository.save(target);

        // Audit manquant (02/08/2026, correctif MOYENNE) : l'activation et
        // surtout la desactivation d'un compte n'etaient jusqu'ici jamais
        // tracees dans le journal d'audit.
        if (authAuditService != null) {
            authAuditService.recordUserEnabledChanged(currentUsername, saved.getUsername(), enabled);
        }
        return saved;
    }

    /**
     * Supprime definitivement un compte utilisateur (03/08/2026, fonctionnalite
     * manquante identifiee lors de la relecture avant publication : seule la
     * desactivation ({@link #setEnabled}) existait jusqu'ici).
     *
     * Suppression physique (hard delete), pas de suppression logique : les
     * tables d'historique (auth_audit_log, backup_runs, backup_restores,
     * revoked_tokens, user_token_invalidations) referencent l'utilisateur par
     * son nom en texte libre, jamais par une cle etrangere vers users(id), donc
     * rien n'y est casse ni orphelin de maniere bloquante. Seules
     * user_roles, user_context_access et mfa_recovery_codes ont une
     * contrainte ON DELETE CASCADE vers users(id) : elles sont nettoyees
     * automatiquement par la base au moment de la suppression.
     *
     * Memes garde-fous que {@link #setEnabled} : un administrateur ne peut ni
     * se supprimer lui-meme, ni supprimer le dernier compte ROLE_ADMIN actif.
     *
     * @param targetId       ID de l'utilisateur a supprimer
     * @param currentUsername administrateur realisant l'action
     * @param ipAddress      adresse IP cliente (pour l'audit, peut etre null)
     * @param userAgent      user-agent HTTP (pour l'audit, peut etre null)
     */
    public void deleteUser(Long targetId, String currentUsername, String ipAddress, String userAgent) {
        User target = findById(targetId);
        User current = findByUsername(currentUsername);

        if (target.getId().equals(current.getId())) {
            throw new AdminLockoutException("Impossible de supprimer votre propre compte.");
        }

        if (hasRole(target, ROLE_ADMIN)) {
            long activeAdminCount = userRepository.countActiveByRoleName(ROLE_ADMIN);
            if (activeAdminCount <= 1) {
                throw new AdminLockoutException(
                        "Impossible de supprimer le dernier administrateur actif.");
            }
        }

        String deletedUsername = target.getUsername();
        userRepository.delete(target);

        if (authAuditService != null) {
            authAuditService.recordUserDeleted(currentUsername, deletedUsername, ipAddress, userAgent);
        }
    }

    // Utilitaires prives

    private Set<Role> resolveRoles(Set<Long> roleIds) {
        Set<Role> resolved = new HashSet<>();
        for (Long roleId : roleIds) {
            Role role = roleRepository.findById(roleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));
            if (!AssignableRoles.contains(role.getName())) {
                throw new IllegalArgumentException("Ce rôle n'est pas attribuable dans Subnetory.");
            }
            resolved.add(role);
        }
        return resolved;
    }

    private Set<NetworkContext> resolveContexts(Set<Long> contextIds) {
        Set<NetworkContext> resolved = new HashSet<>();
        if (contextIds == null) {
            return resolved;
        }
        for (Long contextId : contextIds) {
            NetworkContext context = requireContextRepository().findById(contextId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "NetworkContext", contextId));
            resolved.add(context);
        }
        return resolved;
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim();
    }

    // MFA en self-service (Sprint 2.37 / F8)

    /**
     * Demarre un enrolement MFA : genere un nouveau secret et son QR code.
     * Rien n'est persiste tant que {@link #enableMfa} n'a pas confirme un
     * premier code valide.
     */
    public MfaService.MfaSetup beginMfaSetup(String username) {
        User user = findByUsername(username);
        return requireMfaService().beginSetup(user.getUsername());
    }

    /**
     * Reconstruit le QR code d'un secret d'enrolement deja genere (nouvelle
     * tentative apres un code errone, sans faire tourner un nouveau secret).
     */
    public String buildMfaQrCode(String username, String secret) {
        return requireMfaService().buildQrCodeDataUri(username, secret);
    }

    /**
     * Confirme et active le MFA. Retourne les 10 codes de recuperation,
     * affiches une seule fois.
     */
    public List<String> enableMfa(String username,
                                  String secret,
                                  String confirmationCode,
                                  String ipAddress,
                                  String userAgent) {
        User user = findByUsername(username);
        List<String> recoveryCodes = requireMfaService().activate(user, secret, confirmationCode);

        if (authAuditService != null) {
            authAuditService.recordMfaEnabled(username, ipAddress, userAgent);
        }

        return recoveryCodes;
    }

    /**
     * Desactive le MFA. Exige le mot de passe courant (action qui reduit la
     * securite du compte) et un code MFA valide (TOTP ou recuperation).
     */
    public void disableOwnMfa(String username,
                              String currentPassword,
                              String code,
                              String ipAddress,
                              String userAgent) {
        User user = findByUsername(username);

        if (AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType())) {
            throw new PasswordPolicyException(
                    "Ce compte est gere par LDAP. Contactez un administrateur pour modifier le MFA.");
        }
        if (currentPassword == null || currentPassword.isBlank()
                || user.getPassword() == null
                || !requirePasswordEncoder().matches(currentPassword, user.getPassword())) {
            throw new PasswordPolicyException("Le mot de passe actuel est incorrect.");
        }
        if (!user.isMfaEnabled()) {
            throw new PasswordPolicyException("Le MFA n'est pas active sur ce compte.");
        }
        if (!requireMfaService().verifyChallenge(user, code)) {
            throw new InvalidMfaCodeException();
        }

        requireMfaService().disable(user);

        if (authAuditService != null) {
            authAuditService.recordMfaDisabled(username, ipAddress, userAgent);
        }
    }

    /**
     * Regenere les 10 codes de recuperation. Exige un code MFA valide
     * (TOTP ou recuperation) pour prouver la possession du second facteur
     * avant d'invalider les anciens codes.
     */
    public List<String> regenerateOwnMfaRecoveryCodes(String username,
                                                       String code,
                                                       String ipAddress,
                                                       String userAgent) {
        User user = findByUsername(username);

        if (!user.isMfaEnabled()) {
            throw new PasswordPolicyException("Le MFA n'est pas active sur ce compte.");
        }
        if (!requireMfaService().verifyChallenge(user, code)) {
            throw new InvalidMfaCodeException();
        }

        List<String> codes = requireMfaService().regenerateRecoveryCodes(user);

        if (authAuditService != null) {
            authAuditService.recordMfaRecoveryCodesRegenerated(username, ipAddress, userAgent);
        }

        return codes;
    }

    // MFA anti-lockout administrateur (Sprint 2.37 / Lot 4)

    /**
     * Desactive le MFA d'un compte cible. Action reservee aux administrateurs
     * (ROLE_ADMIN verifie au niveau du controller), destinee au cas ou le
     * titulaire du compte a perdu a la fois son appareil TOTP et ses codes de
     * recuperation. Ne verifie ni mot de passe ni code MFA du compte cible :
     * l'administrateur est deja authentifie et autorise.
     */
    public void adminDisableMfa(Long userId,
                                String adminUsername,
                                String ipAddress,
                                String userAgent) {
        User user = findById(userId);

        if (!user.isMfaEnabled()) {
            throw new PasswordPolicyException("Le MFA n'est pas active sur ce compte.");
        }

        requireMfaService().disable(user);

        if (authAuditService != null) {
            authAuditService.recordMfaDisabledByAdmin(
                    adminUsername, user.getUsername(), ipAddress, userAgent);
        }
    }

    private MfaService requireMfaService() {
        if (mfaService == null) {
            throw new IllegalStateException("MfaService is not configured.");
        }
        return mfaService;
    }

    private boolean hasRole(User user, String roleName) {
        return user.getRoles().stream()
                .anyMatch(r -> roleName.equals(r.getName()));
    }

    private PasswordPolicyService requirePasswordPolicyService() {
        if (passwordPolicyService == null) {
            throw new IllegalStateException("PasswordPolicyService is not configured.");
        }
        return passwordPolicyService;
    }

    private PasswordEncoder requirePasswordEncoder() {
        if (passwordEncoder == null) {
            throw new IllegalStateException("PasswordEncoder is not configured.");
        }
        return passwordEncoder;
    }

    private NetworkContextRepository requireContextRepository() {
        if (contextRepository == null) {
            throw new IllegalStateException("NetworkContextRepository is not configured.");
        }
        return contextRepository;
    }

    private void invalidateAuthorizationTokens(String targetUsername, String actor) {
        if (userTokenInvalidationService != null) {
            userTokenInvalidationService.invalidateTokens(
                    targetUsername,
                    actor,
                    UserTokenInvalidationService.REASON_AUTHORIZATION_CHANGE);
        }
    }
}
