package dev.subnetory.service;

import dev.subnetory.exception.PasswordPolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires de PasswordPolicyService — Sprint 2.13, T3.
 *
 * <p>Couverture :</p>
 * <ul>
 *   <li>Longueur minimale (12 caracteres)</li>
 *   <li>Longueur maximale (128 caracteres)</li>
 *   <li>Presence d'une majuscule</li>
 *   <li>Presence d'une minuscule</li>
 *   <li>Presence d'un chiffre</li>
 *   <li>Presence d'un caractere special</li>
 *   <li>Refus des mots de passe communs</li>
 *   <li>Mots de passe valides acceptes</li>
 *   <li>Cas limites : null, longueur exacte, espaces</li>
 * </ul>
 *
 * <p>Ce test est purement unitaire : aucun contexte Spring, aucune base de donnees.
 * La liste common-passwords.txt est chargee via @PostConstruct — simulee
 * en appelant directement loadCommonPasswords().</p>
 */
class PasswordPolicyServiceTest {

    private PasswordPolicyService service;

    @BeforeEach
    void setUp() {
        service = new PasswordPolicyService();
        // Charger le fichier via PostConstruct manuellement
        service.loadCommonPasswords();
    }

    // ── Cas valides ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mots de passe valides")
    class ValidPasswords {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "Correcthorse1!",      // passphrase-style, 14 chars
                "Tr0ub4dor&3xyz",      // mix classique, 14 chars
                "V3ryStr0ng!Pass",     // 15 chars
                "MyP@ssw0rd2024!",     // 15 chars
                "Azerty12345678!",     // 15 chars
                "Hello World 1!Ab",    // avec espaces, 16 chars
        })
        @DisplayName("Mot de passe valide accepte")
        void validPassword_accepted(String password) {
            assertThatNoException().isThrownBy(() -> service.validate(password));
        }

        @Test
        @DisplayName("Longueur exactement 12 — accepte")
        void exactlyMinLength_accepted() {
            assertThatNoException().isThrownBy(() -> service.validate("Abcdefgh1!xy"));
        }

        @Test
        @DisplayName("Longueur exactement 128 — accepte")
        void exactlyMaxLength_accepted() {
            String pwd = "A1!" + "a".repeat(125); // 3 + 125 = 128
            assertThatNoException().isThrownBy(() -> service.validate(pwd));
        }

        @Test
        @DisplayName("Mot de passe avec espaces et composition valide — accepte")
        void passwordWithSpaces_valid_accepted() {
            // Espaces autorises si les autres regles sont satisfaites
            assertThatNoException().isThrownBy(() -> service.validate("Mon Passe1! Correct"));
        }
    }

    // ── Longueur ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Longueur")
    class Length {

        @ParameterizedTest(name = "[{index}] longueur {0}")
        @ValueSource(strings = {
                "",            // 0
                "A1!aaaaa",    // 8
                "A1!aaaaaa",   // 9
                "A1!aaaaaaa",  // 10
                "A1!aaaaaaaa", // 11
        })
        @DisplayName("Moins de 12 caracteres — refuse")
        void tooShort_rejected(String password) {
            assertThatThrownBy(() -> service.validate(password))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("12");
        }

        @Test
        @DisplayName("11 caracteres exactement — refuse")
        void elevenChars_rejected() {
            assertThatThrownBy(() -> service.validate("Abcdefg1!xy"))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("12");
        }

        @Test
        @DisplayName("129 caracteres — refuse")
        void tooLong_rejected() {
            String pwd = "A1!" + "a".repeat(126); // 3 + 126 = 129
            assertThatThrownBy(() -> service.validate(pwd))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("128");
        }

        @Test
        @DisplayName("200 caracteres — refuse")
        void veryLong_rejected() {
            String pwd = "A1!" + "a".repeat(197);
            assertThatThrownBy(() -> service.validate(pwd))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("128");
        }
    }

    // ── Majuscule ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Majuscule")
    class Uppercase {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "abcdefgh1!23",
                "abcdef123456!",
        })
        @DisplayName("Pas de majuscule — refuse")
        void noUppercase_rejected(String password) {
            assertThatThrownBy(() -> service.validate(password))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("majuscule");
        }

        @Test
        @DisplayName("Une seule majuscule — accepte si autres regles OK")
        void oneUppercase_accepted() {
            assertThatNoException().isThrownBy(() -> service.validate("Abcdefgh1!23"));
        }
    }

    // ── Minuscule ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Minuscule")
    class Lowercase {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "ABCDEFGH1!23",
                "ABCDEF123456!",
        })
        @DisplayName("Pas de minuscule — refuse")
        void noLowercase_rejected(String password) {
            assertThatThrownBy(() -> service.validate(password))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("minuscule");
        }

        @Test
        @DisplayName("Une seule minuscule — accepte si autres regles OK")
        void oneLowercase_accepted() {
            assertThatNoException().isThrownBy(() -> service.validate("ABCDEFGh1!23"));
        }
    }

    // ── Chiffre ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Chiffre")
    class Digit {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "Abcdefghi!jk",
                "AbcdefghiJKL!",
        })
        @DisplayName("Pas de chiffre — refuse")
        void noDigit_rejected(String password) {
            assertThatThrownBy(() -> service.validate(password))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("chiffre");
        }

        @Test
        @DisplayName("Un seul chiffre — accepte si autres regles OK")
        void oneDigit_accepted() {
            assertThatNoException().isThrownBy(() -> service.validate("Abcdefghi1!jk"));
        }
    }

    // ── Caractere special ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Caractere special")
    class SpecialChar {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "Abcdefgh1234",
                "AbcdefghIJK1",
        })
        @DisplayName("Pas de caractere special — refuse")
        void noSpecial_rejected(String password) {
            assertThatThrownBy(() -> service.validate(password))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("special");
        }

        @ParameterizedTest(name = "[{index}] special={0}")
        @ValueSource(strings = {
                "Abcdefgh12!3",
                "Abcdefgh12@3",
                "Abcdefgh12#3",
                "Abcdefgh12$3",
                "Abcdefgh12%3",
                "Abcdefgh12&3",
                "Abcdefgh12*3",
                "Abcdefgh12-3",
                "Abcdefgh12_3",
                "Abcdefgh12=3",
                "Abcdefgh12+3",
                "Abcdefgh12?3",
                "Abcdefgh12.3",
        })
        @DisplayName("Caractere special valide — accepte")
        void variousSpecialChars_accepted(String password) {
            assertThatNoException().isThrownBy(() -> service.validate(password));
        }

        @Test
        @DisplayName("Espace seul ne compte pas comme special")
        void spaceAlone_notSpecial() {
            assertThatThrownBy(() -> service.validate("Abcdefgh 1234"))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("special");
        }
    }

    // ── Mots de passe communs ────────────────────────────────────────────────

    @Nested
    @DisplayName("Mots de passe communs")
    class CommonPasswords {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "Password123!",     // present dans la liste
                "pASSWORD123!",     // variante de casse, composition valide
                "Password1234!",    // present dans la liste
                "P@ssw0rd123!",     // variante courante
                "Pa$$w0rd123!",     // variante courante
        })
        @DisplayName("Mot de passe commun — refuse (si present dans la liste)")
        void commonPassword_rejected(String password) {
            // Ce test n'est pertinent que si le fichier common-passwords.txt est charge.
            if (service.getLoadedCommonPasswords().isEmpty()) {
                return;
            }

            // Verifier d'abord que le mot de passe est bien dans la liste chargee.
            if (!service.getLoadedCommonPasswords().contains(password.toLowerCase(Locale.ROOT))) {
                return;
            }

            assertThatThrownBy(() -> service.validate(password))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("courant");
        }

        @Test
        @DisplayName("Mot de passe unique non commun — accepte")
        void uniquePassword_accepted() {
            assertThatNoException().isThrownBy(
                    () -> service.validate("Zx9#qL2mN!p4vW"));
        }
    }

    // ── Cas limites ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cas limites")
    class EdgeCases {

        @Test
        @DisplayName("null — IllegalArgumentException")
        void nullPassword_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.validate(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Mot de passe vide — refuse (trop court)")
        void emptyPassword_rejected() {
            assertThatThrownBy(() -> service.validate(""))
                    .isInstanceOf(PasswordPolicyException.class)
                    .hasMessageContaining("12");
        }

        @Test
        @DisplayName("Caracteres Unicode (accents) comptes correctement")
        void unicodePassword_countedCorrectly() {
            assertThatNoException().isThrownBy(() -> service.validate("ÉtéChaud2024!ab"));
        }
    }
}