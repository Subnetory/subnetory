package dev.subnetory.repository;

import dev.subnetory.domain.Vlan;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Recherche textuelle sur les VLAN : numéro (VID) et nom. Toujours limitée aux
 * contextes autorisés, via le contexte du site (même règle que la liste existante).
 */
public final class VlanSpecifications {

    private VlanSpecifications() {}

    public static Specification<Vlan> withFilters(String q, Long contextId, Long siteId,
                                                  Collection<Long> allowedContextIds) {
        return (root, query, cb) -> {
            if (allowedContextIds == null || allowedContextIds.isEmpty()) {
                return cb.disjunction();
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("site").get("context").get("id").in(allowedContextIds));
            if (contextId != null) {
                predicates.add(cb.equal(root.get("site").get("context").get("id"), contextId));
            }
            if (siteId != null) {
                predicates.add(cb.equal(root.get("site").get("id"), siteId));
            }
            predicates.addAll(SearchPatterns.termPredicates(cb, q, List.of(
                    p -> SearchPatterns.like(cb, SearchPatterns.asText(root.get("vid")), p),
                    p -> SearchPatterns.like(cb, root.get("name"), p))));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
