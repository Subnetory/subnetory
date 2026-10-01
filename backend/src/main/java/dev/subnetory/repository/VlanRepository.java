package dev.subnetory.repository;

import dev.subnetory.domain.Vlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Collection;

public interface VlanRepository extends JpaRepository<Vlan, Long>, JpaSpecificationExecutor<Vlan> {

    Page<Vlan> findBySiteId(Long siteId, Pageable pageable);

    Page<Vlan> findBySiteContextIdIn(Collection<Long> contextIds, Pageable pageable);

    Page<Vlan> findBySiteContextId(Long contextId, Pageable pageable);

    long countBySiteContextIdIn(Collection<Long> contextIds);

    boolean existsByVidAndSiteId(Short vid, Long siteId);
}
