package dev.subnetory.api.v1;

import dev.subnetory.dto.RequiredPasswordChangeRequest;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.dto.TokenResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.MfaChallengeFailedException;
import dev.subnetory.exception.MfaRequiredException;
import dev.subnetory.exception.PasswordChangeRequiredException;
import dev.subnetory.repository.RevokedTokenRepository;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.JwtLogoutDecoder;
import dev.subnetory.security.JwtTokenService;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.MandatoryPasswordChangeService;
import dev.subnetory.service.MfaLoginChallengeService;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.service.UserTokenInvalidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Emission de tokens JWT pour l'API REST.
 *
 * <h3>Rate limiting (correctif securite H2)</h3>
 * <p>Cet endpoint applique le meme rate limiting par IP que le login Web,
 * via {@link LoginRateLimiter}. Sans cela, l'API constituait un chemin de
 * brute-force sans aucune limite, contournant entierement la protection
 * mise en place sur le formulaire Web au Sprint 2.13.</p>
 *
 * <ul>
 *   <li>IP verrouillee -> 429 Too Many Requests immediatement ;</li>
 *   <li>echec d'authentification -> compteur incremente, audit LOGIN_FAILURE,
 *       delai applique au-dela du seuil ;</li>
 *   <li>succes -> compteur remis a zero, audit LOGIN_SUCCESS.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Jetons API, déconnexion et invalidation de session")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final AuthAuditService authAuditService;
    private final RevokedTokenRepository revokedTokenRepository;
    private final JwtLogoutDecoder jwtLogoutDecoder;
    private final UserTokenInvalidationService userTokenInvalidationService;
    private final MandatoryPasswordChangeService passwordChangeService;
    private final UserAdminService userAdminService;
    private final MfaLoginChallengeService mfaLoginChallengeService;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenService jwtTokenService,
                          LoginRateLimiter loginRateLimiter,
                          ClientIpResolver clientIpResolver,
                          AuthAuditService authAuditService,
                          RevokedTokenRepository revokedTokenRepository,
                          JwtLogoutDecoder jwtLogoutDecoder,
                          UserTokenInvalidationService userTokenInvalidationService,
                          MandatoryPasswordChangeService passwordChangeService,
                          UserAdminService userAdminService,
                          MfaLoginChallengeService mfaLoginChallengeService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenService = jwtTokenService;
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.authAuditService = authAuditService;
        this.revokedTokenRepository = revokedTokenRepository;
        this.jwtLogoutDecoder = jwtLogoutDecoder;
        this.userTokenInvalidationService = userTokenInvalidationService;
        this.passwordChangeService = passwordChangeService;
        this.userAdminService = userAdminService;
        this.mfaLoginChallengeService = mfaLoginChallengeService;
    }

    @PostMapping("/token")
    @Operation(summary = "Obtenir un jeton JWT",
            description = "Authentification par identifiants (locaux ou LDAP). Rate limiting "
                    + "par IP. Répond 403 PASSWORD_CHANGE_REQUIRED tant que le mot de passe "
                    + "temporaire d'un compte local n'a pas été remplacé — voir "
                    + "POST /api/v1/auth/change-password-required. Si le compte a le MFA "
                    + "activé, un champ optionnel totpCode (code TOTP ou de récupération) est "
                    + "requis : 401 MFA_REQUIRED si absent, 401 MFA_INVALID si incorrect.")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request,
                                               HttpServletRequest httpRequest) {
        String ipAddress = clientIpResolver.resolve(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        // 1. Blocage prealable si l'IP OU le nom d'utilisateur est deja verrouille
        // (audit 02/08/2026 : compteur par utilisateur ajoute en complement de
        // l'IP, voir LoginRateLimiter).
        if (loginRateLimiter.isLocked(ipAddress, request.username())) {
            authAuditService.recordLoginLocked(
                    request.username(), ipAddress, userAgent,
                    "Trop de tentatives via l'API. Compte ou IP temporairement bloque.");
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many authentication attempts. Try again later.");
        }

        // 2. Tentative d'authentification.
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            LoginRateLimiter.RateLimitDecision decision = loginRateLimiter.recordFailure(ipAddress, request.username());

            if (decision.locked()) {
                authAuditService.recordLoginLocked(
                        request.username(), ipAddress, userAgent,
                        "Trop de tentatives via l'API. Compte ou IP temporairement bloque.");
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many authentication attempts. Try again later.");
            }

            authAuditService.recordLoginFailure(
                    request.username(), ipAddress, userAgent,
                    "Echec d'authentification via l'API.");

            if (decision.delayed()) {
                sleepQuietly(decision.waitDuration().toMillis());
            }
            // Message generique : pas de fuite sur l'existence du compte.
            throw ex;
        }

        // 3. Defi MFA (Sprint 2.37 / Lot 3) : meme rate limiting que l'echec
        // de mot de passe, pas de compteur separe. Le mot de passe est deja
        // valide a ce stade ; on ne recompte/n'audite le succes qu'apres ce
        // second facteur, pour ne jamais reinitialiser le compteur IP tant
        // que la connexion n'est pas entierement terminee.
        if (mfaLoginChallengeService.isRequired(auth.getName())) {
            boolean codeProvided = StringUtils.hasText(request.totpCode());
            boolean codeValid = codeProvided
                    && mfaLoginChallengeService.verify(auth.getName(), request.totpCode());

            if (!codeValid) {
                applyMfaChallengeFailure(auth.getName(), ipAddress, userAgent);
                throw codeProvided ? new MfaChallengeFailedException() : new MfaRequiredException();
            }
        }

        // 4. Succes : reset du compteur + audit + emission du token.
        loginRateLimiter.recordSuccess(ipAddress, auth.getName());
        authAuditService.recordLoginSuccess(auth.getName(), ipAddress, userAgent);

        if (passwordChangeService.isRequired(auth.getName())) {
            throw new PasswordChangeRequiredException();
        }

        String token = jwtTokenService.generateToken(auth);
        return ResponseEntity.ok(TokenResponse.of(token, jwtTokenService.getExpirationMinutes()));
    }

    private void applyMfaChallengeFailure(String username, String ipAddress, String userAgent) {
        LoginRateLimiter.RateLimitDecision decision = loginRateLimiter.recordFailure(ipAddress, username);

        if (decision.locked()) {
            authAuditService.recordLoginLocked(
                    username, ipAddress, userAgent,
                    "Trop de tentatives via l'API (code MFA). Compte ou IP temporairement bloque.");
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many authentication attempts. Try again later.");
        }

        authAuditService.recordMfaChallengeFailed(username, ipAddress, userAgent);

        if (decision.delayed()) {
            sleepQuietly(decision.waitDuration().toMillis());
        }
    }

    /**
     * Remplacement du mot de passe temporaire via l'API, sans JWT prealable.
     *
     * <p>Ce endpoint complete le parcours API-first des comptes d'automatisation :
     * un compte local nouvellement cree (mustChangePassword=true) ne peut pas
     * obtenir de JWT tant que son mot de passe temporaire n'a pas ete remplace.
     * Sans ce endpoint, le seul chemin de deblocage etait le formulaire Web.</p>
     *
     * <p>Securite :</p>
     * <ul>
     *   <li>meme rate limiting par IP que /token (verrouillage, delais) ;</li>
     *   <li>authentification complete par identifiants avant toute action ;</li>
     *   <li>reponse 401 generique : aucune fuite sur l'existence du compte ;</li>
     *   <li>politique de mot de passe identique au parcours Web ;</li>
     *   <li>aucun JWT emis par ce endpoint : le client appelle ensuite /token ;</li>
     *   <li>audit PASSWORD_CHANGE et invalidation des tokens via le service commun.</li>
     * </ul>
     */
    @PostMapping("/change-password-required")
    @Operation(
            summary = "Remplacer un mot de passe temporaire sans JWT",
            description = "Permet à un compte local dont le changement de mot de passe est "
                    + "obligatoire (compte nouvellement créé ou réinitialisé par un "
                    + "administrateur) de remplacer son mot de passe temporaire par API, "
                    + "sans passer par l'interface web. Aucun jeton n'est délivré : après "
                    + "succès (204), appeler POST /api/v1/auth/token avec le nouveau mot "
                    + "de passe. Répond 401 si les identifiants sont invalides, 400 si le "
                    + "nouveau mot de passe ne respecte pas la politique, 409 si le compte "
                    + "n'a pas de changement obligatoire en attente.")
    public ResponseEntity<Void> changeRequiredPassword(
            @Valid @RequestBody RequiredPasswordChangeRequest request,
            HttpServletRequest httpRequest) {

        String ipAddress = clientIpResolver.resolve(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        if (loginRateLimiter.isLocked(ipAddress, request.username())) {
            authAuditService.recordLoginLocked(
                    request.username(), ipAddress, userAgent,
                    "Trop de tentatives via l'API. Compte ou IP temporairement bloque.");
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many authentication attempts. Try again later.");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.username(), request.currentPassword()));
        } catch (AuthenticationException ex) {
            LoginRateLimiter.RateLimitDecision decision = loginRateLimiter.recordFailure(ipAddress, request.username());

            if (decision.locked()) {
                authAuditService.recordLoginLocked(
                        request.username(), ipAddress, userAgent,
                        "Trop de tentatives via l'API. Compte ou IP temporairement bloque.");
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many authentication attempts. Try again later.");
            }

            authAuditService.recordLoginFailure(
                    request.username(), ipAddress, userAgent,
                    "Echec d'authentification via l'API.");

            if (decision.delayed()) {
                sleepQuietly(decision.waitDuration().toMillis());
            }
            // Message generique : pas de fuite sur l'existence du compte.
            throw ex;
        }

        loginRateLimiter.recordSuccess(ipAddress, request.username());

        if (!passwordChangeService.isRequired(request.username())) {
            throw new ConflictException(
                    "No pending mandatory password change for this account.");
        }

        userAdminService.changeOwnPassword(
                request.username(),
                request.currentPassword(),
                request.newPassword(),
                ipAddress,
                userAgent);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    @Transactional
    @Operation(summary = "Révoquer le jeton JWT courant",
            description = "Révocation idempotente du jeton porté par l'en-tête Authorization : "
                    + "deux appels successifs avec le même jeton répondent 204.")
    public ResponseEntity<?> logout(HttpServletRequest httpRequest) {
        Jwt jwt = decodeLogoutToken(httpRequest);
        String jti = jwt.getId();

        if (!StringUtils.hasText(jti)) {
            return ResponseEntity.ok(Map.of(
                    "status", "not_revocable",
                    "message", "Token has no jti claim and will expire naturally."
            ));
        }

        if (jwt.getExpiresAt() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT has no expiration.");
        }

        String username = StringUtils.hasText(jwt.getSubject()) ? jwt.getSubject() : "unknown";
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(jwt.getExpiresAt(), ZoneOffset.UTC);

        int inserted = revokedTokenRepository.insertIfAbsent(jti, username, expiresAt, "LOGOUT");
        if (inserted > 0) {
            authAuditService.recordTokenRevoked(
                    username,
                    clientIpResolver.resolve(httpRequest),
                    httpRequest.getHeader("User-Agent"),
                    jti);
        }

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Invalider tous les jetons du compte",
            description = "Invalide tous les JWT émis pour le compte authentifié, y compris "
                    + "celui utilisé pour cet appel.")
    public ResponseEntity<Void> logoutAll(Authentication authentication,
                                          HttpServletRequest httpRequest) {
        if (authentication == null || !StringUtils.hasText(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated subject.");
        }

        String username = authentication.getName();
        String reason = UserTokenInvalidationService.REASON_LOGOUT_ALL;

        userTokenInvalidationService.invalidateTokens(username, username, reason);
        authAuditService.recordTokensInvalidated(
                username,
                username,
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"),
                reason);

        return ResponseEntity.noContent().build();
    }

    private Jwt decodeLogoutToken(HttpServletRequest httpRequest) {
        String authorization = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }

        String token = authorization.substring(7);
        try {
            return jwtLogoutDecoder.decode(token);
        } catch (JwtException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer token.", ex);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
