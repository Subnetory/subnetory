package dev.subnetory.service;

import dev.subnetory.domain.BackupSettings;
import dev.subnetory.repository.BackupSettingsRepository;
import dev.subnetory.web.form.BackupSettingsForm;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 audit, 31/07/2026 — meme pattern de test que
 * {@link LdapConfigurationServiceTest} pour le meme pattern de service
 * (configuration singleton en base, valeurs par defaut application.yml).
 */
class BackupConfigurationServiceTest {

    private final BackupSettingsRepository repository = mock(BackupSettingsRepository.class);
    private final AuthAuditService authAuditService = mock(AuthAuditService.class);
    private final BackupConfigurationService service = new BackupConfigurationService(repository, authAuditService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "fallbackEnabled", false);
        ReflectionTestUtils.setField(service, "fallbackCron", "0 0 2 * * *");
        ReflectionTestUtils.setField(service, "fallbackRetentionCount", 14);
    }

    // -------------------------------------------------------
    // effectiveSettings
    // -------------------------------------------------------

    @Test
    void effectiveSettings_usesFallbackPropertiesWhenNoRowExists() {
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.empty());

        var settings = service.effectiveSettings();

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.cronExpression()).isEqualTo("0 0 2 * * *");
        assertThat(settings.retentionCount()).isEqualTo(14);
    }

    @Test
    void effectiveSettings_usesDbRowWhenItExists() {
        BackupSettings row = new BackupSettings();
        row.setId(BackupSettings.SINGLETON_ID);
        row.setEnabled(true);
        row.setCronExpression("0 30 3 * * *");
        row.setRetentionCount(7);
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.of(row));

        var settings = service.effectiveSettings();

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.cronExpression()).isEqualTo("0 30 3 * * *");
        assertThat(settings.retentionCount()).isEqualTo(7);
    }

    @Test
    void form_isPopulatedFromEffectiveSettings() {
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.empty());

        BackupSettingsForm form = service.form();

        assertThat(form.isEnabled()).isFalse();
        assertThat(form.getCronExpression()).isEqualTo("0 0 2 * * *");
        assertThat(form.getRetentionCount()).isEqualTo(14);
    }

    // -------------------------------------------------------
    // save — création / mise à jour
    // -------------------------------------------------------

    @Test
    void save_createsSingletonRowWhenNoneExists() {
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.empty());

        BackupSettings saved = doSave(form(true, "0 0 3 * * *", 21));

        assertThat(saved.getId()).isEqualTo(BackupSettings.SINGLETON_ID);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getCronExpression()).isEqualTo("0 0 3 * * *");
        assertThat(saved.getRetentionCount()).isEqualTo(21);
    }

    @Test
    void save_updatesExistingRowInPlace() {
        BackupSettings existing = new BackupSettings();
        existing.setId(BackupSettings.SINGLETON_ID);
        existing.setEnabled(false);
        existing.setCronExpression("0 0 2 * * *");
        existing.setRetentionCount(14);
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any(BackupSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(form(true, "0 15 4 * * *", 30));

        assertThat(existing.isEnabled()).isTrue();
        assertThat(existing.getCronExpression()).isEqualTo("0 15 4 * * *");
        assertThat(existing.getRetentionCount()).isEqualTo(30);
    }

    // -------------------------------------------------------
    // save — validation
    // -------------------------------------------------------

    @Test
    void save_rejectsBlankCron() {
        assertThatThrownBy(() -> service.save(form(true, "  ", 14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expression cron obligatoire.");

        verify(repository, never()).save(any());
    }

    @Test
    void save_rejectsMalformedCron() {
        assertThatThrownBy(() -> service.save(form(true, "not a cron", 14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expression cron invalide");

        verify(repository, never()).save(any());
    }

    @Test
    void save_rejectsRetentionBelowMinimum() {
        assertThatThrownBy(() -> service.save(form(true, "0 0 2 * * *", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compris entre 1 et 365");

        verify(repository, never()).save(any());
    }

    @Test
    void save_rejectsRetentionAboveMaximum() {
        assertThatThrownBy(() -> service.save(form(true, "0 0 2 * * *", 366)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compris entre 1 et 365");

        verify(repository, never()).save(any());
    }

    @Test
    void save_acceptsRetentionAtBoundaries() {
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.empty());

        BackupSettings min = doSave(form(true, "0 0 2 * * *", 1));
        assertThat(min.getRetentionCount()).isEqualTo(1);

        BackupSettings max = doSave(form(true, "0 0 2 * * *", 365));
        assertThat(max.getRetentionCount()).isEqualTo(365);
    }

    // -------------------------------------------------------
    // nextRunAt
    // -------------------------------------------------------

    @Test
    void nextRunAt_returnsNullWhenDisabled() {
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "fallbackEnabled", false);

        assertThat(service.nextRunAt(OffsetDateTime.now())).isNull();
    }

    @Test
    void nextRunAt_returnsNullWhenStoredCronIsInvalid() {
        BackupSettings row = new BackupSettings();
        row.setId(BackupSettings.SINGLETON_ID);
        row.setEnabled(true);
        row.setCronExpression("garbage");
        row.setRetentionCount(14);
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.of(row));

        assertThat(service.nextRunAt(OffsetDateTime.now())).isNull();
    }

    @Test
    void nextRunAt_returnsNextFireTimeWhenEnabledAndValid() {
        BackupSettings row = new BackupSettings();
        row.setId(BackupSettings.SINGLETON_ID);
        row.setEnabled(true);
        row.setCronExpression("0 0 2 * * *");
        row.setRetentionCount(14);
        when(repository.findById(BackupSettings.SINGLETON_ID)).thenReturn(Optional.of(row));

        OffsetDateTime reference = OffsetDateTime.parse("2026-07-31T10:00:00Z");
        OffsetDateTime next = service.nextRunAt(reference);

        assertThat(next).isNotNull();
        assertThat(next).isAfter(reference);
        assertThat(next.getHour()).isEqualTo(2);
        assertThat(next.getMinute()).isZero();
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private BackupSettings doSave(BackupSettingsForm form) {
        final BackupSettings[] saved = new BackupSettings[1];
        when(repository.save(any(BackupSettings.class))).thenAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        service.save(form);
        return saved[0];
    }

    private BackupSettingsForm form(boolean enabled, String cron, int retentionCount) {
        BackupSettingsForm form = new BackupSettingsForm();
        form.setEnabled(enabled);
        form.setCronExpression(cron);
        form.setRetentionCount(retentionCount);
        return form;
    }
}
