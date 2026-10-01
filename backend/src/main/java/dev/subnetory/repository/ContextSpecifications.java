package dev.subnetory.repository;

import dev.subnetory.domain.NetworkContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Recherche textuelle sur les contextes : nom et description. Limitée aux contextes autorisés. */
public final class ContextSpecifications {

    private ContextSpecifications() {}

    public static Specification<NetworkContext> withFilters(String q, Collection<Long> allowedContextIds) {
        return (root, query, cb) -> {
            if (allowedContextIds == null || allowedContextIds.isEmpty()) {
                return cb.disjunction();
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("id").in(allowedContextIds));
            predicates.addAll(SearchPatterns.termPredicates(cb, q, List.of(
                    p -> SearchPatterns.like(cb, root.get("name"), p),
                    p -> SearchPatterns.like(cb, root.get("description"), p))));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
