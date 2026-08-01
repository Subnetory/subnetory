package dev.subnetory.repository;

import dev.subnetory.domain.Address;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Specifications JPA pour la recherche multi-critères sur les adresses.
 *
 * <p>Chaque critère est optionnel. Les critères fournis sont combinés en AND.
 * Utilisé par {@link AddressRepository} via {@code JpaSpecificationExecutor}.</p>
 */
public class AddressSpecifications {

    private AddressSpecifications() {}

    /**
     * Construit une Specification combinant tous les critères non-null fournis.
     *
     * @param hostname       hostname contenant cette valeur (insensible à la casse)
     * @param hostnameContains hostname contient cette chaîne (insensible à la casse)
     * @param mac            correspondance exacte sur mac
     * @param q              recherche multi-termes sur les champs utiles de l'inventaire
     * @param siteId         filtre sur site
     * @param contextId      filtre sur contexte
     * @param subnetId       filtre sur subnet
     */
    public static Specification<Address> withFilters(
            String hostname,
            String hostnameContains,
            String mac,
            String q,
            Long siteId,
            Long contextId,
            Long subnetId,
            Collection<Long> allowedContextIds) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (allowedContextIds == null || allowedContextIds.isEmpty()) {
                return cb.disjunction();
            }

            predicates.add(root.get("context").get("id").in(allowedContextIds));

            if (hostname != null && !hostname.isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("hostname")),
                        containsPattern(hostname), '\\'));
            }

            if (hostnameContains != null && !hostnameContains.isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("hostname")),
                        containsPattern(hostnameContains), '\\'));
            }

            if (mac != null && !mac.isBlank()) {
                // MACADDR est un type natif PostgreSQL — lower() ne s'applique pas dessus.
                // PostgreSQL stocke macaddr en minuscules nativement (ex: aa:bb:cc:dd:ee:ff).
                predicates.add(cb.equal(
                        asText(cb, root.get("mac")),
                        mac.toLowerCase().trim()));
            }

            if (q != null && !q.isBlank()) {
                String normalized = q.trim();
                if (normalized.length() > 120) normalized = normalized.substring(0, 120);
                String[] terms = normalized.split("\\s+");
                for (int i = 0; i < Math.min(terms.length, 6); i++) {
                    String term = terms[i];
                    String pattern = containsPattern(term);
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("hostname")), pattern, '\\'),
                            cb.like(cb.lower(root.get("description")), pattern, '\\'),
                            cb.like(cb.lower(asText(cb, root.get("address"))), pattern, '\\'),
                            cb.like(cb.lower(asText(cb, root.get("mac"))), pattern, '\\'),
                            cb.like(cb.lower(asText(cb, root.get("subnet").get("network"))), pattern, '\\'),
                            cb.like(cb.lower(root.get("site").get("name")), pattern, '\\'),
                            cb.like(cb.lower(root.get("site").get("code")), pattern, '\\'),
                            cb.like(cb.lower(root.get("context").get("name")), pattern, '\\'),
                            cb.like(cb.lower(root.get("discoverySource")), pattern, '\\')
                    ));
                }
            }

            if (siteId != null) {
                predicates.add(cb.equal(root.get("site").get("id"), siteId));
            }

            if (contextId != null) {
                predicates.add(cb.equal(root.get("context").get("id"), contextId));
            }

            if (subnetId != null) {
                predicates.add(cb.equal(root.get("subnet").get("id"), subnetId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Convertit une colonne native PostgreSQL (inet, macaddr, cidr) en texte.
     *
     * <p>Hibernate 6.6 n'émettait plus de cast SQL pour {@code as(String.class)}
     * lorsque le type Java de l'attribut était déjà {@code String} : le SQL généré
     * appliquait alors {@code lower()} directement sur la colonne native, ce que
     * PostgreSQL refuse. Depuis Jakarta Persistence 3.2 (Hibernate 7 / Spring Boot 4),
     * {@link Expression#cast(Class)} est une méthode standard de la spec qui provoque
     * une véritable conversion de type — contrairement à {@code as()}, qui ne fait que
     * changer la vue Java sans toucher au SQL généré. On utilise donc ce cast standard
     * plutôt que l'ancienne rustine de concaténation avec une chaîne vide.</p>
     */
    private static Expression<String> asText(CriteriaBuilder cb, Expression<?> column) {
        return column.cast(String.class);
    }

    /** Échappe les jokers SQL afin que le texte saisi reste littéral. */
    private static String containsPattern(String value) {
        String escaped = value.toLowerCase(java.util.Locale.ROOT).trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
