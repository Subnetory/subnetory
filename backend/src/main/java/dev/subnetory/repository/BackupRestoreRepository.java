package dev.subnetory.repository;

import dev.subnetory.domain.BackupRestore;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupRestoreRepository extends JpaRepository<BackupRestore, Long> {

    Page<BackupRestore> findAllByOrderByStartedAtDesc(Pageable pageable);

    /** Utilise au demarrage pour reconcilier les restaurations orphelines (voir BackupRunRepository#findByStatus). */
    List<BackupRestore> findByStatus(String status);

    /** Candidates a la purge manuelle explicite (audit 01/08/2026). */
    List<BackupRestore> findByStartedAtBeforeAndStatusNot(OffsetDateTime cutoff, String excludedStatus);

    /**
     * Protege l'integrite referentielle lors d'une purge : un
     * {@link dev.subnetory.domain.BackupRun} encore reference (comme source
     * ou comme sauvegarde de securite) par une restauration NON purgee ne
     * doit jamais etre supprime, meme s'il est lui-meme plus ancien que la
     * date de coupure.
     */
    boolean existsByBackupRunIdOrSafetyBackupRunId(Long backupRunId, Long safetyBackupRunId);

    /**
     * Restaurations liees a un {@link dev.subnetory.domain.BackupRun} donne
     * (comme source ou comme sauvegarde de securite), utilisee pour la
     * suppression fine avec confirmation (audit 01/08/2026) : avant de
     * proposer une suppression en cascade, l'IHM doit pouvoir lister
     * precisement quelles restaurations seraient perdues.
     */
    List<BackupRestore> findByBackupRunIdOrSafetyBackupRunId(Long backupRunId, Long safetyBackupRunId);
}
