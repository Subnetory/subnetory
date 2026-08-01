package dev.subnetory.repository;

import dev.subnetory.domain.NetworkContext;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NetworkContextRepository extends JpaRepository<NetworkContext, Long> {

    boolean existsByName(String name);

    Page<NetworkContext> findByIdIn(Collection<Long> ids, Pageable pageable);

    List<NetworkContext> findByIdInOrderByNameAsc(Collection<Long> ids);

    @Query("select c.id from NetworkContext c order by c.id")
    List<Long> findAllIds();
}
