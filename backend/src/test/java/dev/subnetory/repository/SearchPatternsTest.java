package dev.subnetory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchPatternsTest {

    @Test
    @DisplayName("Saisie vide ou blanche : aucun terme")
    void blankInput_noTerms() {
        assertThat(SearchPatterns.terms(null)).isEmpty();
        assertThat(SearchPatterns.terms("   ")).isEmpty();
    }

    @Test
    @DisplayName("Termes séparés par des espaces, 6 au plus")
    void termsAreSplitAndLimited() {
        assertThat(SearchPatterns.terms("  a  b c ")).containsExactly("a", "b", "c");
        assertThat(SearchPatterns.terms("1 2 3 4 5 6 7 8")).hasSize(6);
    }

    @Test
    @DisplayName("Saisie tronquée à 120 caractères")
    void inputIsTruncated() {
        List<String> terms = SearchPatterns.terms("x".repeat(500));
        assertThat(terms).hasSize(1);
        assertThat(terms.get(0)).hasSize(120);
    }

    @Test
    @DisplayName("Motif : minuscules et jokers SQL échappés")
    void containsPattern_escapesWildcards() {
        assertThat(SearchPatterns.containsPattern("AbC")).isEqualTo("%abc%");
        assertThat(SearchPatterns.containsPattern("50%_x\\")).isEqualTo("%50\\%\\_x\\\\%");
    }
}
