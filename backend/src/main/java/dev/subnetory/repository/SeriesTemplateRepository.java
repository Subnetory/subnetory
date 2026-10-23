package dev.subnetory.repository;

import dev.subnetory.domain.SeriesTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SeriesTemplateRepository extends JpaRepository<SeriesTemplate, Long> {

    List<SeriesTemplate> findByContextIdInOrderByNameAsc(Collection<Long> contextIds);

    List<SeriesTemplate> findByContextIdOrderByNameAsc(Long contextId);

    boolean existsByContextIdAndNameIgnoreCase(Long contextId, String name);

    boolean existsByContextIdAndNameIgnoreCaseAndIdNot(Long contextId, String name, Long id);
}
