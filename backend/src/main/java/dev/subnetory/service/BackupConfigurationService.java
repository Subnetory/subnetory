package dev.subnetory.service;

import dev.subnetory.domain.BackupSettings;
import dev.subnetory.repository.BackupSettingsRepository;
import dev.subnetory.web.form.BackupSettingsForm;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuration de la sauvegarde automatique (Phase 7 audit, 31/07/2026).
 *
 * <p>Meme pattern que {@link LdapConfigurationService} : une ligne en base
 * (singleton {@link BackupSettings#SINGLETON_ID}) prévaut sur les valeurs
 * par défaut de {@code application.yml} tant qu'aucune configuration n'a
 * été enregistrée par un administrateur.</p>
 */
@Service
@Transactional
public class BackupConfigurationService {

    private static final int MIN_RETENTION = 1;
    private static final int MAX_RETENTION = 365;

    private final BackupSettingsRepository repository;
    private final AuthAuditService authAuditService;

    @Value("${subnetory.backup.enabled:false}")
    private boolean fallbackEnabled;

    @Value("${subnetory.backup.cron:0 0 2 * * *}")
    private String fallbackCron;

    @Value("${subnetory.backup.retention-count:14}")
    private int fallbackRetentionCount;

    public BackupConfigurationService(BackupSettingsRepository repository, AuthAuditService authAuditService) {
        this.repository = repository;
        this.authAuditService = authAuditService;
    }

    /** Meme pattern que {@code ContextAccessService#currentAuthentication} (audit 01/08/2026, backlog #27). */
    private String currentUsername() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        return authentication.getName();
    }

    @Transactional(readOnly = true)
    public EffectiveBackupSettings effectiveSettings() {
        return repository.findById(BackupSettings.SINGLETON_ID)
                .map(this::fromEntity)
                .orElseGet(this::fromProperties);
    }

    @Transactional(readOnly = true)
    public BackupSettingsForm form() {
        EffectiveBackupSettings settings = effectiveSettings();
        BackupSettingsForm form = new BackupSettingsForm();
        form.setEnabled(settings.enabled());
        form.setCronExpression(settings.cronExpression());
        form.setRetentionCount(settings.retentionCount());
        return form;
    }

    public void save(BackupSettingsForm form) {
        String cron = required(form.getCronExpression(), "Expression cron");
        validateCron(cron);
        int retention = form.getRetentionCount();
        if (retention < MIN_RETENTION || retention > MAX_RETENTION) {
            throw new IllegalArgumentException(
                    "Le nombre de sauvegardes à conserver doit être compris entre "
                            + MIN_RETENTION + " et " + MAX_RETENTION + ".");
        }

        Optional<BackupSettings> existing = repository.findById(BackupSettings.SINGLETON_ID);
        BackupSettings settings = existing.orElseGet(() -> {
            BackupSettings created = new BackupSettings();
            created.setId(BackupSettings.SINGLETON_ID);
            return created;
        });
        settings.setEnabled(form.isEnabled());
        settings.setCronExpression(cron);
        settings.setRetentionCount(retention);
        repository.save(settings);
        authAuditService.recordBackupSettingsUpdated(currentUsername(), cron, retention, form.isEnabled());
    }

    /** Prochaine exécution planifiée, ou {@code null} si désactivé ou expression invalide. */
    @Transactional(readOnly = true)
    public java.time.OffsetDateTime nextRunAt(java.time.OffsetDateTime reference) {
        EffectiveBackupSettings settings = effectiveSettings();
        if (!settings.enabled()) return null;
        try {
            return CronExpression.parse(settings.cronExpression()).next(reference);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void validateCron(String cron) {
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Expression cron invalide (format à 6 champs : secondes minutes heures "
                            + "jour-du-mois mois jour-de-semaine). " + e.getMessage());
        }
    }

    private EffectiveBackupSettings fromEntity(BackupSettings settings) {
        return new EffectiveBackupSettings(
                settings.isEnabled(), settings.getCronExpression(), settings.getRetentionCount());
    }

    private EffectiveBackupSettings fromProperties() {
        return new EffectiveBackupSettings(fallbackEnabled, fallbackCron, fallbackRetentionCount);
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " obligatoire.");
        }
        return value.trim();
    }

    public record EffectiveBackupSettings(
            boolean enabled,
            String cronExpression,
            int retentionCount
    ) {}
}
