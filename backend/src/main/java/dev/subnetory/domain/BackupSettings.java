package dev.subnetory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Configuration de la sauvegarde automatique de la base (Phase 7 audit,
 * 31/07/2026). Table singleton : une seule ligne, {@link #SINGLETON_ID}.
 *
 * <p>Meme pattern que {@link LdapSettings} : une configuration persistee en
 * base, modifiable depuis l'IHM admin, avec des valeurs par defaut fournies
 * par {@code application.yml} tant qu'aucune ligne n'existe.</p>
 */
@Entity
@Table(name = "backup_settings")
public class BackupSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression;

    @Column(name = "retention_count", nullable = false)
    private int retentionCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public int getRetentionCount() { return retentionCount; }
    public void setRetentionCount(int retentionCount) { this.retentionCount = retentionCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
