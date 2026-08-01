package dev.subnetory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Historique d'une execution de sauvegarde (Phase 7 audit, 31/07/2026).
 *
 * <p>Une ligne par execution de {@code pg_dump}, qu'elle soit declenchee par
 * le planificateur interne, par un administrateur (bouton "Sauvegarder
 * maintenant"), ou automatiquement juste avant une restauration (sauvegarde
 * de securite — {@link #TRIGGER_PRE_RESTORE_SAFETY}).</p>
 */
@Entity
@Table(name = "backup_runs")
public class BackupRun {

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_PRE_RESTORE_SAFETY = "PRE_RESTORE_SAFETY";
    /** Sauvegarde ajoutee a l'historique via l'import d'un fichier .dump telecharge (audit 01/08/2026). */
    public static final String TRIGGER_IMPORTED = "IMPORTED";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trigger_source", nullable = false, length = 30)
    private String triggerSource;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "triggered_by")
    private String triggeredBy;

    /** Titre/commentaire optionnel saisi par l'administrateur (audit 01/08/2026). */
    @Column(name = "label", length = 200)
    private String label;

    /**
     * {@code true} si le fichier {@code .dump} sur disque est chiffré
     * (AES-256-GCM + HMAC-SHA256, audit 01/08/2026 — voir
     * {@code dev.subnetory.backup.BackupExecutionService}). Absent de la clé
     * de dérivation, un fichier chiffré n'est ni lisible ni restaurable :
     * cette information reste purement indicative pour l'IHM/l'API, jamais
     * utilisée pour reconstituer un secret.
     */
    @Column(name = "encrypted", nullable = false)
    private boolean encrypted;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public boolean isSuccess() { return STATUS_SUCCESS.equals(status); }
    public boolean isFailed() { return STATUS_FAILED.equals(status); }
    public boolean isRunning() { return STATUS_RUNNING.equals(status); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
