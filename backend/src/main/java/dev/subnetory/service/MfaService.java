package dev.subnetory.service;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.recovery.RecoveryCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.util.Utils;
import dev.subnetory.domain.MfaRecoveryCode;
import dev.subnetory.domain.User;
import dev.subnetory.exception.InvalidMfaCodeException;
import dev.subnetory.repository.MfaRecoveryCodeRepository;
import dev.subnetory.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Service metier MFA (TOTP, RFC 6238) - Sprint 2.37 / F8.
 *
 * <p>Le secret TOTP n'est jamais persiste en clair : il transite chiffre via
 * {@link SecretCipherService}, deja utilise pour le mot de passe du bind LDAP
 * (meme mecanisme, aucune nouvelle primitive cryptographique). Les codes de
 * recuperation sont, comme le mot de passe utilisateur, uniquement stockes
 * sous forme de hash bcrypt via le {@link PasswordEncoder} existant.</p>
 */
@Service
public class MfaService {

    private static final String ISSUER = "Subnetory";
    private static final int RECOVERY_CODE_COUNT = 10;

    private final UserRepository userRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final SecretCipherService secretCipherService;
    private final PasswordEncoder passwordEncoder;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();
    private final CodeVerifier codeVerifier =
            new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
    private final RecoveryCodeGenerator recoveryCodeGenerator = new RecoveryCodeGenerator();

    public MfaService(UserRepository userRepository,
                       MfaRecoveryCodeRepository recoveryCodeRepository,
                       SecretCipherService secretCipherService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.secretCipherService = secretCipherService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Demarre un enrolement : genere un nouveau secret et le QR code
     * correspondant. Le secret n'est pas encore persiste : il n'est active
     * qu'apres confirmation d'un premier code valide via {@link #activate}.
     */
    public MfaSetup beginSetup(String username) {
        String secret = secretGenerator.generate();
        return new MfaSetup(secret, buildQrCodeDataUri(username, secret));
    }

    /**
     * Reconstruit le QR code d'un secret deja genere (ex: nouvelle tentative
     * apres un code de confirmation errone, sans faire tourner un nouveau
     * secret et forcer un nouveau scan).
     */
    public String buildQrCodeDataUri(String username, String secret) {
        QrData data = new QrData.Builder()
                .label(username)
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        try {
            byte[] png = qrGenerator.generate(data);
            return Utils.getDataUriForImage(png, qrGenerator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new IllegalStateException("Echec de generation du QR code MFA.", e);
        }
    }

    /**
     * Confirme et active le MFA pour un utilisateur : exige un premier code
     * TOTP valide sur le secret propose par {@link #beginSetup}. Genere et
     * retourne les codes de recuperation (affiches une seule fois).
     */
    @Transactional
    public List<String> activate(User user, String secret, String confirmationCode) {
        if (!codeVerifier.isValidCode(secret, confirmationCode)) {
            throw new InvalidMfaCodeException();
        }
        user.setMfaEnabled(true);
        user.setMfaSecretEncrypted(secretCipherService.encrypt(secret));
        userRepository.save(user);
        return regenerateRecoveryCodes(user);
    }

    /**
     * Desactive le MFA : supprime le secret et tous les codes de recuperation
     * existants. Utilise par le self-service (avec verification prealable du
     * mot de passe et d'un code) et par l'action anti-lockout admin.
     */
    @Transactional
    public void disable(User user) {
        user.setMfaEnabled(false);
        user.setMfaSecretEncrypted(null);
        userRepository.save(user);
        recoveryCodeRepository.deleteByUserId(user.getId());
    }

    /**
     * Regenere les 10 codes de recuperation : invalide tous les anciens
     * (utilises ou non) et en cree 10 nouveaux. Retourne les codes en clair,
     * une seule fois : ils ne sont jamais recuperables ensuite.
     */
    @Transactional
    public List<String> regenerateRecoveryCodes(User user) {
        recoveryCodeRepository.deleteByUserId(user.getId());
        String[] codes = recoveryCodeGenerator.generateCodes(RECOVERY_CODE_COUNT);
        OffsetDateTime now = OffsetDateTime.now();
        for (String code : codes) {
            MfaRecoveryCode entity = new MfaRecoveryCode();
            entity.setUserId(user.getId());
            entity.setCodeHash(passwordEncoder.encode(code));
            entity.setCreatedAt(now);
            recoveryCodeRepository.save(entity);
        }
        return List.of(codes);
    }

    /**
     * Verifie un code TOTP par rapport au secret actuellement actif de
     * l'utilisateur (dechiffre a la volee, jamais expose en dehors de ce
     * service).
     */
    public boolean verifyTotpCode(User user, String code) {
        if (!user.isMfaEnabled() || user.getMfaSecretEncrypted() == null || code == null || code.isBlank()) {
            return false;
        }
        String secret = secretCipherService.decrypt(user.getMfaSecretEncrypted());
        return codeVerifier.isValidCode(secret, code);
    }

    /**
     * Verifie un code de recuperation et le consomme (marque comme utilise)
     * s'il correspond. Chaque code n'est utilisable qu'une seule fois.
     */
    @Transactional
    public boolean verifyAndConsumeRecoveryCode(User user, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        List<MfaRecoveryCode> unused = recoveryCodeRepository.findUnusedByUserId(user.getId());
        for (MfaRecoveryCode candidate : unused) {
            if (passwordEncoder.matches(code, candidate.getCodeHash())) {
                candidate.setUsedAt(OffsetDateTime.now());
                recoveryCodeRepository.save(candidate);
                return true;
            }
        }
        return false;
    }

    /**
     * Verifie un code soumis lors d'un defi MFA (login Web ou API) : accepte
     * indifferemment un code TOTP valide ou un code de recuperation non
     * consomme.
     */
    public boolean verifyChallenge(User user, String code) {
        return verifyTotpCode(user, code) || verifyAndConsumeRecoveryCode(user, code);
    }

    /** Resultat du demarrage d'enrolement : secret en clair (non persiste) et QR code en Data URI. */
    public record MfaSetup(String secret, String qrCodeDataUri) {}
}
