package dev.subnetory.service;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.subnetory.domain.MfaRecoveryCode;
import dev.subnetory.domain.User;
import dev.subnetory.exception.InvalidMfaCodeException;
import dev.subnetory.repository.MfaRecoveryCodeRepository;
import dev.subnetory.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    private static final String TEST_JWT_SECRET = "test-secret-at-least-32-characters-long!!";

    @Mock UserRepository userRepository;
    @Mock MfaRecoveryCodeRepository recoveryCodeRepository;

    PasswordEncoder passwordEncoder;
    SecretCipherService secretCipherService;
    MfaService service;
    User user;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4); // strength faible : tests rapides
        secretCipherService = new SecretCipherService(TEST_JWT_SECRET, TEST_JWT_SECRET);
        service = new MfaService(userRepository, recoveryCodeRepository, secretCipherService, passwordEncoder);

        user = new User();
        user.setId(1L);
        user.setUsername("alice");
    }

    private String currentValidCode(String secret) {
        try {
            long counter = Math.floorDiv(Instant.now().getEpochSecond(), 30);
            return new DefaultCodeGenerator().generate(secret, counter);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void beginSetup_returnsSecretAndQrCodeDataUri() {
        MfaService.MfaSetup setup = service.beginSetup("alice");

        assertThat(setup.secret()).isNotBlank();
        assertThat(setup.qrCodeDataUri()).startsWith("data:image/png;base64,");
    }

    @Test
    void activate_withValidCode_enablesMfaAndReturnsTenRecoveryCodes() {
        MfaService.MfaSetup setup = service.beginSetup("alice");
        String validCode = currentValidCode(setup.secret());

        List<String> recoveryCodes = service.activate(user, setup.secret(), validCode);

        assertThat(user.isMfaEnabled()).isTrue();
        assertThat(user.getMfaSecretEncrypted()).isNotBlank().isNotEqualTo(setup.secret());
        assertThat(recoveryCodes).hasSize(10);
        assertThat(recoveryCodes).doesNotHaveDuplicates();
        verify(userRepository).save(user);
        verify(recoveryCodeRepository, org.mockito.Mockito.times(10)).save(any(MfaRecoveryCode.class));
    }

    @Test
    void activate_withInvalidCode_throwsAndDoesNotEnableMfa() {
        MfaService.MfaSetup setup = service.beginSetup("alice");

        assertThatThrownBy(() -> service.activate(user, setup.secret(), "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);

        assertThat(user.isMfaEnabled()).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void disable_clearsSecretAndDeletesRecoveryCodes() {
        user.setMfaEnabled(true);
        user.setMfaSecretEncrypted(secretCipherService.encrypt("SOMESECRET"));

        service.disable(user);

        assertThat(user.isMfaEnabled()).isFalse();
        assertThat(user.getMfaSecretEncrypted()).isNull();
        verify(userRepository).save(user);
        verify(recoveryCodeRepository).deleteByUserId(1L);
    }

    @Test
    void verifyTotpCode_validCode_returnsTrue() {
        String secret = service.beginSetup("alice").secret();
        user.setMfaEnabled(true);
        user.setMfaSecretEncrypted(secretCipherService.encrypt(secret));

        assertThat(service.verifyTotpCode(user, currentValidCode(secret))).isTrue();
    }

    @Test
    void verifyTotpCode_invalidCode_returnsFalse() {
        String secret = service.beginSetup("alice").secret();
        user.setMfaEnabled(true);
        user.setMfaSecretEncrypted(secretCipherService.encrypt(secret));

        assertThat(service.verifyTotpCode(user, "000000")).isFalse();
    }

    @Test
    void verifyTotpCode_mfaDisabled_returnsFalse() {
        assertThat(service.verifyTotpCode(user, "123456")).isFalse();
    }

    @Test
    void verifyAndConsumeRecoveryCode_matchingCode_marksUsedAndReturnsTrue() {
        String rawCode = "abcd-efgh-ijkl-mnop";
        MfaRecoveryCode stored = new MfaRecoveryCode();
        stored.setId(42L);
        stored.setUserId(1L);
        stored.setCodeHash(passwordEncoder.encode(rawCode));
        stored.setCreatedAt(OffsetDateTime.now());
        when(recoveryCodeRepository.findUnusedByUserId(1L)).thenReturn(List.of(stored));
        when(recoveryCodeRepository.markUsedIfUnused(eq(42L), any())).thenReturn(1);

        boolean result = service.verifyAndConsumeRecoveryCode(user, rawCode);

        assertThat(result).isTrue();
        verify(recoveryCodeRepository).markUsedIfUnused(eq(42L), any());
    }

    /**
     * Regression (audit du 03/08/2026, correctif MOYEN) : si
     * {@code markUsedIfUnused} renvoie 0 lignes affectees (un autre appelant
     * concurrent a deja consomme ce code entre la lecture et cette mise a
     * jour), le code doit etre traite comme invalide, pas comme accepte.
     */
    @Test
    void verifyAndConsumeRecoveryCode_concurrentlyAlreadyConsumed_returnsFalse() {
        String rawCode = "abcd-efgh-ijkl-mnop";
        MfaRecoveryCode stored = new MfaRecoveryCode();
        stored.setId(42L);
        stored.setUserId(1L);
        stored.setCodeHash(passwordEncoder.encode(rawCode));
        when(recoveryCodeRepository.findUnusedByUserId(1L)).thenReturn(List.of(stored));
        when(recoveryCodeRepository.markUsedIfUnused(eq(42L), any())).thenReturn(0);

        boolean result = service.verifyAndConsumeRecoveryCode(user, rawCode);

        assertThat(result).isFalse();
    }

    @Test
    void verifyAndConsumeRecoveryCode_noMatch_returnsFalseAndDoesNotSave() {
        MfaRecoveryCode stored = new MfaRecoveryCode();
        stored.setUserId(1L);
        stored.setCodeHash(passwordEncoder.encode("abcd-efgh-ijkl-mnop"));
        when(recoveryCodeRepository.findUnusedByUserId(1L)).thenReturn(List.of(stored));

        boolean result = service.verifyAndConsumeRecoveryCode(user, "wrong-code-1234");

        assertThat(result).isFalse();
        verify(recoveryCodeRepository, never()).markUsedIfUnused(any(), any());
    }

    @Test
    void verifyAndConsumeRecoveryCode_noCodesLeft_returnsFalse() {
        when(recoveryCodeRepository.findUnusedByUserId(anyLong())).thenReturn(List.of());

        assertThat(service.verifyAndConsumeRecoveryCode(user, "anything")).isFalse();
    }

    @Test
    void verifyChallenge_acceptsTotpOrRecoveryCode() {
        String secret = service.beginSetup("alice").secret();
        user.setMfaEnabled(true);
        user.setMfaSecretEncrypted(secretCipherService.encrypt(secret));

        // Code TOTP valide : verifyChallenge doit l'accepter sans meme consulter
        // les codes de recuperation (court-circuit sur verifyTotpCode).
        assertThat(service.verifyChallenge(user, currentValidCode(secret))).isTrue();
    }

    @Test
    void regenerateRecoveryCodes_deletesOldAndCreatesTenNew() {
        List<String> codes = service.regenerateRecoveryCodes(user);

        assertThat(codes).hasSize(10);
        verify(recoveryCodeRepository).deleteByUserId(1L);
        verify(recoveryCodeRepository, org.mockito.Mockito.times(10)).save(any(MfaRecoveryCode.class));
    }
}
