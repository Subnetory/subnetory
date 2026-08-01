package dev.subnetory.repository;

import dev.subnetory.domain.Site;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface SiteRepository extends JpaRepository<Site, Long> {

    Page<Site> findByContextId(Long contextId, Pageable pageable);

    Page<Site> findByContextIdIn(Collection<Long> contextIds, Pageable pageable);

    List<Site> findByContextIdIn(Collection<Long> contextIds);

    long countByContextIdIn(Collection<Long> contextIds);

    boolean existsByCode(String code);
}
