package dev.subnetory.repository;

import dev.subnetory.domain.BackupRun;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupRunRepository extends JpaRepository<BackupRun, Long> {

    Page<BackupRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    Optional<BackupRun> findFirstByStatusOrderByStartedAtDesc(String status);

    /** Derniere execution, tous declencheurs confondus — sert de reference au planificateur. */
    Optional<BackupRun> findFirstByOrderByStartedAtDesc();

    /**
     * Sauvegardes reussies, de la plus recente a la plus ancienne — utilise
     * pour appliquer la retention (garder les N premieres, purger le reste).
     */
    List<BackupRun> findByStatusOrderByStartedAtDesc(String status);

    long countByStatus(String status);

    /**
     * Utilise au demarrage de l'application pour reconcilier les executions
     * orphelines (JVM tuee en plein pg_dump — aucune ligne RUNNING ne peut
     * legitimement survivre a un redemarrage, voir
     * {@code BackupExecutionService#reconcileOrphanedOperations}).
     */
    List<BackupRun> findByStatus(String status);

    /**
     * Candidates a la purge manuelle explicite (audit 01/08/2026) : jamais
     * les lignes RUNNING, meme anciennes (protection minimale, la
     * reconciliation au demarrage devrait de toute facon les avoir deja
     * traitees — voir {@code BackupExecutionService#purgeHistoryBefore}).
     */
    List<BackupRun> findByStartedAtBeforeAndStatusNot(OffsetDateTime cutoff, String excludedStatus);
}
