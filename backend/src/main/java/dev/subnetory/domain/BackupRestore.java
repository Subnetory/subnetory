package dev.subnetory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Journal d'une operation de restauration (Phase 7 audit, 31/07/2026).
 *
 * <p>Table distincte de {@link BackupRun} : une restauration n'est pas une
 * sauvegarde, et cette tracabilite est exigee pour une operation aussi
 * sensible (cf. {@code backend/docs/RESTORE_OPERATIONS.md}).</p>
 */
@Entity
@Table(name = "backup_restores")
public class BackupRestore {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "backup_run_id", nullable = false)
    private Long backupRunId;

    @Column(name = "safety_backup_run_id")
    private Long safetyBackupRunId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "performed_by", nullable = false)
    private String performedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBackupRunId() { return backupRunId; }
    public void setBackupRunId(Long backupRunId) { this.backupRunId = backupRunId; }
    public Long getSafetyBackupRunId() { return safetyBackupRunId; }
    public void setSafetyBackupRunId(Long safetyBackupRunId) { this.safetyBackupRunId = safetyBackupRunId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getPerformedBy() { return performedBy; }
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
