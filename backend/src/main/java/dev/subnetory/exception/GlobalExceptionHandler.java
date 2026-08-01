package dev.subnetory.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gestionnaire d'erreurs global — RFC 7807 ProblemDetail.
 *
 * <p><b>Scope volontairement restreint à {@code dev.subnetory.api}</b> (audit
 * du 31/07/2026) : avant, {@code @RestControllerAdvice} sans scoping
 * s'appliquait aussi aux contrôleurs web Thymeleaf sous {@code dev.subnetory.web},
 * qui recevaient alors du JSON brut au lieu des templates {@code error/403.html},
 * {@code error/404.html}, {@code error/500.html} déjà écrits mais quasiment
 * inatteignables en pratique. Sans cette classe pour les intercepter, les
 * exceptions non gérées localement dans un contrôleur web propagent
 * maintenant vers le mécanisme d'erreur par défaut de Spring Boot
 * ({@code BasicErrorController} + {@code DefaultErrorViewResolver}), qui
 * résout automatiquement {@code error/<status>.html} par convention.
 *
 * Codes HTTP garantis (API {@code /api/v1/**}) :
 *   400 — validation Bean Validation
 *   401 — credentials invalides, token manquant/expiré
 *   403 — accès refusé (@PreAuthorize)
 *   404 — ressource inexistante
 *   409 — conflit métier (doublon) ou contrainte FK/unique DB
 *   500 — erreur interne non gérée
 */
@RestControllerAdvice(basePackages = "dev.subnetory.api")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_BASE = "https://subnetory.dev/errors/";

    // -------------------------------------------------------
    // 400 — Validation
    // -------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (a, b) -> a
                ));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setType(URI.create(ERROR_BASE + "validation-error"));
        pd.setTitle("Validation Error");
        pd.setProperty("fields", fields);
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 401 — Authentification
    // -------------------------------------------------------

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid credentials");
        pd.setType(URI.create(ERROR_BASE + "authentication-failed"));
        pd.setTitle("Authentication Failed");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication failed");
        pd.setType(URI.create(ERROR_BASE + "authentication-failed"));
        pd.setTitle("Authentication Failed");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 403 — Autorisation
    // -------------------------------------------------------

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Access denied");
        pd.setType(URI.create(ERROR_BASE + "access-denied"));
        pd.setTitle("Access Denied");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(PasswordChangeRequiredException.class)
    public ProblemDetail handlePasswordChangeRequired(
            PasswordChangeRequiredException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, ex.getMessage());
        pd.setType(URI.create(ERROR_BASE + "password-change-required"));
        pd.setTitle("Password Change Required");
        pd.setProperty("code", "PASSWORD_CHANGE_REQUIRED");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 404 — Ressource inexistante
    // -------------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create(ERROR_BASE + "not-found"));
        pd.setTitle("Resource Not Found");
        pd.setProperty("resourceType", ex.getResourceType());
        pd.setProperty("identifier", ex.getIdentifier());
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 400 — Politique de mot de passe
    // -------------------------------------------------------

    /**
     * Le message de {@link PasswordPolicyException} est destiné à l'utilisateur
     * final (règle violée, mot de passe actuel incorrect, compte LDAP) et ne
     * contient aucune information technique sensible : il est donc restitué
     * tel quel dans le corps de la réponse.
     */
    @ExceptionHandler(PasswordPolicyException.class)
    public ProblemDetail handlePasswordPolicy(PasswordPolicyException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create(ERROR_BASE + "password-policy"));
        pd.setTitle("Password Policy Violation");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 400 — MFA (Sprint 2.37 / F8)
    // -------------------------------------------------------

    @ExceptionHandler(InvalidMfaCodeException.class)
    public ProblemDetail handleInvalidMfaCode(InvalidMfaCodeException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create(ERROR_BASE + "invalid-mfa-code"));
        pd.setTitle("Invalid MFA Code");
        pd.setProperty("code", "MFA_INVALID");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 401 — Defi MFA au login (Sprint 2.37 / Lot 3)
    // -------------------------------------------------------

    @ExceptionHandler(MfaRequiredException.class)
    public ProblemDetail handleMfaRequired(MfaRequiredException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setType(URI.create(ERROR_BASE + "mfa-required"));
        pd.setTitle("MFA Required");
        pd.setProperty("code", "MFA_REQUIRED");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(MfaChallengeFailedException.class)
    public ProblemDetail handleMfaChallengeFailed(MfaChallengeFailedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setType(URI.create(ERROR_BASE + "mfa-invalid"));
        pd.setTitle("Invalid MFA Code");
        pd.setProperty("code", "MFA_INVALID");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 409 — Conflits (métier + contraintes DB)
    // -------------------------------------------------------

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(ERROR_BASE + "conflict"));
        pd.setTitle("Conflict");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    /**
     * Verrouillage optimiste (audit du 31/07/2026, {@code Address}/{@code Subnet}
     * uniquement) : la ressource a été modifiée par quelqu'un d'autre entre
     * la lecture et l'écriture. Message explicite plutôt qu'un 500 générique
     * ou un écrasement silencieux.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This resource was modified by someone else in the meantime. Reload and retry.");
        pd.setType(URI.create(ERROR_BASE + "concurrent-modification"));
        pd.setTitle("Concurrent Modification");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    /**
     * Capture les violations de contraintes PostgreSQL (FK, UNIQUE) non interceptées
     * par les vérifications métier dans les services.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        // Signale une contrainte DB (FK/UNIQUE) atteinte sans avoir ete
        // anticipee par une verification metier explicite dans le service
        // -- utile a surveiller, contrairement a ConflictException qui est
        // deja un cas metier normal et attendu.
        log.warn("Data integrity violation: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Operation conflicts with existing data");
        pd.setType(URI.create(ERROR_BASE + "data-integrity-conflict"));
        pd.setTitle("Data Integrity Conflict");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // Statuts explicites (ex: 429 rate limiting sur /api/v1/auth/token)
    // -------------------------------------------------------

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                ex.getStatusCode(),
                ex.getReason() != null ? ex.getReason() : "Request failed");
        pd.setType(URI.create(ERROR_BASE + "request-error"));
        pd.setTitle("Request Error");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 404 / 405 — Routage (aucun handler / mauvais verbe HTTP)
    // -------------------------------------------------------
    // Sans ces deux handlers explicites, ces exceptions (qui portent deja
    // leur propre statut HTTP correct) retombent dans le catch-all
    // Exception.class ci-dessous et sont ecrasees en 500 "unexpected error" —
    // un cas reel rencontre via un vieux lien "returnTo" pointant vers un
    // endpoint POST-only apres un rendu de formulaire sans redirect.

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        // Signal utile pour reperer d'eventuels pieges POST/redirect
        // similaires a celui corrige le 30/07/2026 (currentRequestPath
        // pointant vers une route POST-only) sans attendre qu'un
        // utilisateur le signale.
        log.warn("405 Method Not Allowed: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
        pd.setType(URI.create(ERROR_BASE + "method-not-allowed"));
        pd.setTitle("Method Not Allowed");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ProblemDetail handleNoHandlerFound(NoHandlerFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "No handler found for this request");
        pd.setType(URI.create(ERROR_BASE + "not-found"));
        pd.setTitle("Resource Not Found");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    // -------------------------------------------------------
    // 500 — Erreur interne
    // -------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        // Sans ce log, une erreur 500 imprevue ne laisse aucune trace cote
        // serveur : le client recoit une reponse propre, mais personne ne
        // peut diagnostiquer la cause reelle (audit du 31/07/2026).
        log.error("Unexpected error handled by GlobalExceptionHandler", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setType(URI.create(ERROR_BASE + "internal-error"));
        pd.setTitle("Internal Server Error");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
