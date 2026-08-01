package dev.subnetory.service;

import dev.subnetory.domain.User;
import dev.subnetory.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Determine si un second facteur (TOTP ou code de recuperation) est requis
 * pour terminer la connexion, et verifie le code fourni.
 *
 * Sprint 2.37 / Lot 3. Meme role au login que {@link MfaService} pour le
 * self-service : point d'entree unique utilise par {@code AuthController}
 * (API) et {@code MfaChallengeFilter} / {@code MfaChallengeWebController}
 * (Web), sur le meme modele que {@link MandatoryPasswordChangeService}.
 */
@Service
public class MfaLoginChallengeService {

    private final UserRepository userRepository;
    private final MfaService mfaService;

    public MfaLoginChallengeService(UserRepository userRepository, MfaService mfaService) {
        this.userRepository = userRepository;
        this.mfaService = mfaService;
    }

    @Transactional(readOnly = true)
    public boolean isRequired(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }

        return userRepository.findByUsername(username)
                .filter(User::isEnabled)
                .map(User::isMfaEnabled)
                .orElse(false);
    }

    @Transactional
    public boolean verify(String username, String code) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(code)) {
            return false;
        }

        return userRepository.findByUsername(username)
                .filter(User::isEnabled)
                .filter(User::isMfaEnabled)
                .map(user -> mfaService.verifyChallenge(user, code))
                .orElse(false);
    }
}
