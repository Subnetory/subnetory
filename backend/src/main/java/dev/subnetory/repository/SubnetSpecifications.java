package dev.subnetory.repository;

import dev.subnetory.domain.Subnet;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Recherche textuelle sur les sous-réseaux : CIDR, description et passerelle.
 * Filtre toujours sur le {@code context_id} propre du sous-réseau (audits des
 * 03/08/2026), jamais seulement sur celui de son site.
 */
public final class SubnetSpecifications {

    private SubnetSpecifications() {}

    public static Specification<Subnet> withFilters(String q, Long contextId, Long siteId, Long vlanId,
                                                    Collection<Long> allowedContextIds) {
        return (root, query, cb) -> {
            if (allowedContextIds == null || allowedContextIds.isEmpty()) {
                return cb.disjunction();
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("context").get("id").in(allowedContextIds));
            if (contextId != null) {
                predicates.add(cb.equal(root.get("context").get("id"), contextId));
            }
            if (siteId != null) {
                predicates.add(cb.equal(root.get("site").get("id"), siteId));
            }
            if (vlanId != null) {
                predicates.add(cb.equal(root.get("vlan").get("id"), vlanId));
            }
            predicates.addAll(SearchPatterns.termPredicates(cb, q, List.of(
                    p -> SearchPatterns.like(cb, SearchPatterns.asText(root.get("network")), p),
                    p -> SearchPatterns.like(cb, root.get("description"), p),
                    p -> SearchPatterns.like(cb, SearchPatterns.asText(root.get("gateway")), p))));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
