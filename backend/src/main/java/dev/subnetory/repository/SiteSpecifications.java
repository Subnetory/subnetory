package dev.subnetory.repository;

import dev.subnetory.domain.Site;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Recherche textuelle sur les sites : nom et code. Toujours limitée aux contextes autorisés. */
public final class SiteSpecifications {

    private SiteSpecifications() {}

    public static Specification<Site> withFilters(String q, Long contextId, Collection<Long> allowedContextIds) {
        return (root, query, cb) -> {
            if (allowedContextIds == null || allowedContextIds.isEmpty()) {
                return cb.disjunction();
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("context").get("id").in(allowedContextIds));
            if (contextId != null) {
                predicates.add(cb.equal(root.get("context").get("id"), contextId));
            }
            predicates.addAll(SearchPatterns.termPredicates(cb, q, List.of(
                    p -> SearchPatterns.like(cb, root.get("name"), p),
                    p -> SearchPatterns.like(cb, root.get("code"), p))));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
