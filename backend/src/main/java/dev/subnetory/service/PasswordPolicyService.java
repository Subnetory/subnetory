package dev.subnetory.service;

import dev.subnetory.exception.PasswordPolicyException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Service de validation de la politique de mot de passe Subnetory.
 *
 * <p>Politique appliquee :</p>
 * <ul>
 *   <li>Longueur minimale : 12 caracteres</li>
 *   <li>Longueur maximale : 128 caracteres</li>
 *   <li>Au moins 1 lettre majuscule</li>
 *   <li>Au moins 1 lettre minuscule</li>
 *   <li>Au moins 1 chiffre</li>
 *   <li>Au moins 1 caractere special (non alphanumerique, non espace)</li>
 *   <li>Refus des mots de passe presents dans la liste des mots de passe communs</li>
 * </ul>
 *
 * <p>La liste des mots de passe communs est chargee depuis
 * {@code classpath:security/common-passwords.txt} au demarrage.
 * Si le fichier est absent, un avertissement est logue et la verification
 * est desactivee sans bloquer le demarrage.</p>
 *
 * <p>Les espaces sont autorises mais ne remplacent pas les exigences ci-dessus.</p>
 *
 * <p>Ce service est appele depuis {@code UserAdminService.changeOwnPassword}
 * et {@code UserAdminService.adminResetPassword}. Les comptes LDAP ne
 * passent jamais par ce service (filtre en amont dans UserAdminService).</p>
 */
@Service
public class PasswordPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicyService.class);

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;
    private static final String COMMON_PASSWORDS_PATH = "security/common-passwords.txt";

    private Set<String> commonPasswords = new HashSet<>();

    @PostConstruct
    void loadCommonPasswords() {
        ClassPathResource resource = new ClassPathResource(COMMON_PASSWORDS_PATH);
        if (!resource.exists()) {
            log.warn("common-passwords.txt not found at classpath:{}. "
                    + "Common password check is disabled.", COMMON_PASSWORDS_PATH);
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    commonPasswords.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
            log.debug("Loaded {} common passwords.", commonPasswords.size());
        } catch (IOException e) {
            log.warn("Failed to load common-passwords.txt. "
                    + "Common password check is disabled. Cause: {}", e.getMessage());
        }
    }

    /**
     * Valide un mot de passe selon la politique Subnetory.
     *
     * @param password le mot de passe en clair a valider
     * @throws PasswordPolicyException si le mot de passe ne respecte pas la politique,
     *                                  avec un message clair destine a l'utilisateur
     * @throws IllegalArgumentException si password est null
     */
    public void validate(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }

        if (password.length() < MIN_LENGTH) {
            throw new PasswordPolicyException(
                    "Le mot de passe doit contenir au moins " + MIN_LENGTH + " caracteres.");
        }

        if (password.length() > MAX_LENGTH) {
            throw new PasswordPolicyException(
                    "Le mot de passe ne peut pas depasser " + MAX_LENGTH + " caracteres.");
        }

        if (!hasUppercase(password)) {
            throw new PasswordPolicyException(
                    "Le mot de passe doit contenir au moins une lettre majuscule.");
        }

        if (!hasLowercase(password)) {
            throw new PasswordPolicyException(
                    "Le mot de passe doit contenir au moins une lettre minuscule.");
        }

        if (!hasDigit(password)) {
            throw new PasswordPolicyException(
                    "Le mot de passe doit contenir au moins un chiffre.");
        }

        if (!hasSpecial(password)) {
            throw new PasswordPolicyException(
                    "Le mot de passe doit contenir au moins un caractere special "
                    + "(ex : ! @ # $ % & * - _ = + ? .).");
        }

        if (!commonPasswords.isEmpty() && isCommon(password)) {
            throw new PasswordPolicyException(
                    "Ce mot de passe est trop courant. Veuillez en choisir un plus unique.");
        }
    }

    // ── Predicats prives ────────────────────────────────────────────────────

    private boolean hasUppercase(String pwd) {
        return pwd.chars().anyMatch(Character::isUpperCase);
    }

    private boolean hasLowercase(String pwd) {
        return pwd.chars().anyMatch(Character::isLowerCase);
    }

    private boolean hasDigit(String pwd) {
        return pwd.chars().anyMatch(Character::isDigit);
    }

    /**
     * Un caractere special est defini comme : non alphanumerique et non espace.
     * Inclut : ! @ # $ % ^ & * - _ = + ? . , ; : / \ | ( ) [ ] { } < > ~ `
     */
    private boolean hasSpecial(String pwd) {
        return pwd.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
    }

    private boolean isCommon(String pwd) {
        return commonPasswords.contains(pwd.toLowerCase(Locale.ROOT));
    }

    // ── Accesseur package-private pour les tests ────────────────────────────

    Set<String> getLoadedCommonPasswords() {
        return java.util.Collections.unmodifiableSet(commonPasswords);
    }
}
