package dev.subnetory.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Utilitaires communs aux recherches textuelles des listes (sites, VLAN,
 * sous-réseaux, contextes). Même comportement que la recherche des adresses IP :
 * termes séparés par des espaces, combinés en ET, correspondance partielle
 * insensible à la casse, jokers SQL saisis par l'utilisateur traités littéralement.
 */
public final class SearchPatterns {

    static final int MAX_QUERY_LENGTH = 120;
    static final int MAX_TERMS = 6;

    private SearchPatterns() {}

    /** Découpe la saisie en termes (120 caractères et 6 termes au plus). */
    public static List<String> terms(String q) {
        if (q == null || q.isBlank()) return List.of();
        String normalized = q.trim();
        if (normalized.length() > MAX_QUERY_LENGTH) {
            normalized = normalized.substring(0, MAX_QUERY_LENGTH);
        }
        String[] parts = normalized.split("\\s+");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(parts.length, MAX_TERMS); i++) {
            if (!parts[i].isBlank()) result.add(parts[i]);
        }
        return result;
    }

    /** Motif {@code %terme%} en minuscules, avec jokers SQL échappés (échappement {@code \}). */
    public static String containsPattern(String value) {
        String escaped = value.toLowerCase(Locale.ROOT).trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Convertit une colonne native PostgreSQL (inet, cidr...) ou numérique en texte. */
    public static Expression<String> asText(Expression<?> column) {
        return column.cast(String.class);
    }

    /**
     * Un prédicat par terme ; chacun est un OU sur les expressions fournies.
     * Les prédicats sont à combiner en ET avec les autres critères.
     */
    public static List<Predicate> termPredicates(CriteriaBuilder cb, String q,
                                                 List<Function<String, Predicate>> matchers) {
        List<Predicate> predicates = new ArrayList<>();
        for (String term : terms(q)) {
            String pattern = containsPattern(term);
            Predicate[] ors = matchers.stream()
                    .map(m -> m.apply(pattern))
                    .toArray(Predicate[]::new);
            predicates.add(cb.or(ors));
        }
        return predicates;
    }

    public static Predicate like(CriteriaBuilder cb, Expression<String> textExpression, String pattern) {
        return cb.like(cb.lower(textExpression), pattern, '\\');
    }
}
