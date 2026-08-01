package dev.subnetory.api.v1;

import dev.subnetory.backup.BackupException;
import dev.subnetory.backup.BackupExecutionService;
import dev.subnetory.domain.BackupRestore;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.dto.BackupPurgeRequest;
import dev.subnetory.dto.BackupRestoreRequest;
import dev.subnetory.dto.BackupSettingsRequest;
import dev.subnetory.dto.BackupSettingsResponse;
import dev.subnetory.dto.BackupTriggerRequest;
import dev.subnetory.repository.BackupRestoreRepository;
import dev.subnetory.repository.BackupRunRepository;
import dev.subnetory.service.BackupConfigurationService;
import dev.subnetory.service.BackupConfigurationService.EffectiveBackupSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 audit, 31/07/2026 (etendu le 01/08/2026) — meme pattern que
 * {@link AdminLdapControllerTest} : instanciation directe du controleur avec
 * des dependances mockees, sans contexte Spring (rapide, deterministe,
 * couvre le mapping DTO / statuts HTTP).
 */
class AdminBackupControllerTest {

    private final BackupConfigurationService configurationService = mock(BackupConfigurationService.class);
    private final BackupExecutionService executionService = mock(BackupExecutionService.class);
    private final BackupRunRepository backupRunRepository = mock(BackupRunRepository.class);
    private final BackupRestoreRepository backupRestoreRepository = mock(BackupRestoreRepository.class);
    private final AdminBackupController controller = new AdminBackupController(
            configurationService, executionService, backupRunRepository, backupRestoreRepository);

    @TempDir Path tempDir;

    // -------------------------------------------------------
    // GET /api/v1/admin/backup
    // -------------------------------------------------------

    @Test
    void getSettings_noPriorSuccessfulRun_lastRunIsNull() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(true, "0 0 2 * * *", 14));
        when(backupRunRepository.findFirstByStatusOrderByStartedAtDesc(BackupRun.STATUS_SUCCESS))
                .thenReturn(Optional.empty());
        when(configurationService.nextRunAt(any())).thenReturn(OffsetDateTime.parse("2026-08-01T02:00:00Z"));
        when(executionService.storagePath()).thenReturn("/var/subnetory/backups");
        when(backupRunRepository.countByStatus(BackupRun.STATUS_SUCCESS)).thenReturn(0L);
        when(executionService.totalStorageBytes()).thenReturn(0L);

        BackupSettingsResponse response = controller.getSettings();

        assertThat(response.enabled()).isTrue();
        assertThat(response.cronExpression()).isEqualTo("0 0 2 * * *");
        assertThat(response.retentionCount()).isEqualTo(14);
        assertThat(response.lastRun()).isNull();
        assertThat(response.nextRunAt()).isEqualTo(OffsetDateTime.parse("2026-08-01T02:00:00Z"));
    }

    @Test
    void getSettings_withPriorSuccessfulRun_includesLastRun() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(true, "0 0 2 * * *", 14));
        BackupRun lastRun = sampleRun(3L, BackupRun.STATUS_SUCCESS);
        when(backupRunRepository.findFirstByStatusOrderByStartedAtDesc(BackupRun.STATUS_SUCCESS))
                .thenReturn(Optional.of(lastRun));
        when(configurationService.nextRunAt(any())).thenReturn(null);
        when(executionService.storagePath()).thenReturn("/var/subnetory/backups");
        when(backupRunRepository.countByStatus(BackupRun.STATUS_SUCCESS)).thenReturn(5L);
        when(executionService.totalStorageBytes()).thenReturn(10_485_760L);

        BackupSettingsResponse response = controller.getSettings();

        assertThat(response.lastRun()).isNotNull();
        assertThat(response.lastRun().id()).isEqualTo(3L);
        assertThat(response.totalBackupCount()).isEqualTo(5L);
        assertThat(response.totalStorageBytes()).isEqualTo(10_485_760L);
    }

    // -------------------------------------------------------
    // PUT /api/v1/admin/backup
    // -------------------------------------------------------

    @Test
    void updateSettings_valid_delegatesAndReturnsUpdatedSettings() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(true, "0 0 3 * * *", 30));
        when(backupRunRepository.findFirstByStatusOrderByStartedAtDesc(BackupRun.STATUS_SUCCESS))
                .thenReturn(Optional.empty());
        when(executionService.storagePath()).thenReturn("/var/subnetory/backups");

        var response = controller.updateSettings(new BackupSettingsRequest(true, "0 0 3 * * *", 30));

        verify(configurationService).save(any());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((BackupSettingsResponse) response.getBody()).cronExpression()).isEqualTo("0 0 3 * * *");
    }

    @Test
    void updateSettings_invalidCron_returns400WithProblemDetail() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Expression cron invalide."))
                .when(configurationService).save(any());

        var response = controller.updateSettings(new BackupSettingsRequest(true, "garbage", 14));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------
    // GET /api/v1/admin/backup/runs
    // -------------------------------------------------------

    @Test
    void listRuns_mapsPageOfRunsToResponses() {
        BackupRun run = sampleRun(1L, BackupRun.STATUS_SUCCESS);
        when(backupRunRepository.findAllByOrderByStartedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(run)));

        Page<?> page = controller.listRuns(0, 20);

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void listRuns_capsPageSizeAt100() {
        when(backupRunRepository.findAllByOrderByStartedAtDesc(any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.listRuns(0, 500);

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(backupRunRepository).findAllByOrderByStartedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    // -------------------------------------------------------
    // POST /api/v1/admin/backup/trigger
    // -------------------------------------------------------

    @Test
    void trigger_success_returns200WithRun() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        BackupRun run = sampleRun(7L, BackupRun.STATUS_SUCCESS);
        when(executionService.triggerManualBackup("admin", null)).thenReturn(run);

        var response = controller.trigger(null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void trigger_withLabel_passesLabelToService() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        BackupRun run = sampleRun(7L, BackupRun.STATUS_SUCCESS);
        when(executionService.triggerManualBackup("admin", "avant migration V19")).thenReturn(run);

        var response = controller.trigger(new BackupTriggerRequest("avant migration V19"), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(executionService).triggerManualBackup("admin", "avant migration V19");
    }

    @Test
    void trigger_toolNotAvailable_returns503() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        when(executionService.triggerManualBackup(eq("admin"), any())).thenThrow(new BackupException(
                "pg_dump n'est pas installé ou introuvable dans le PATH du conteneur.",
                BackupException.Reason.TOOL_NOT_AVAILABLE));

        var response = controller.trigger(null, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // -------------------------------------------------------
    // POST /api/v1/admin/backup/import (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void importBackup_success_returns200WithRun() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        BackupRun run = sampleRun(11L, BackupRun.STATUS_SUCCESS);
        run.setTriggerSource(BackupRun.TRIGGER_IMPORTED);
        MockMultipartFile file = new MockMultipartFile(
                "file", "external.dump", "application/octet-stream", "dump-bytes".getBytes());
        when(executionService.importBackup(any(), eq("admin"))).thenReturn(run);

        var response = controller.importBackup(file, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void importBackup_invalidFormat_returns500() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        MockMultipartFile file = new MockMultipartFile(
                "file", "not-a-dump.txt", "text/plain", "garbage".getBytes());
        when(executionService.importBackup(any(), eq("admin"))).thenThrow(new BackupException(
                "Le fichier importé ne semble pas être une sauvegarde pg_dump valide.",
                BackupException.Reason.EXECUTION_FAILED));

        var response = controller.importBackup(file, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void importBackup_toolNotAvailable_returns503() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        MockMultipartFile file = new MockMultipartFile(
                "file", "external.dump", "application/octet-stream", "dump-bytes".getBytes());
        when(executionService.importBackup(any(), eq("admin"))).thenThrow(new BackupException(
                "pg_restore n'est pas installé ou introuvable dans le PATH du conteneur.",
                BackupException.Reason.TOOL_NOT_AVAILABLE));

        var response = controller.importBackup(file, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // -------------------------------------------------------
    // POST /api/v1/admin/backup/purge (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void purge_delegatesCutoffAndReturnsCounts() {
        when(executionService.purgeHistoryBefore(any()))
                .thenReturn(new BackupExecutionService.PurgeResult(4, 2));

        var response = controller.purge(new BackupPurgeRequest(LocalDate.of(2026, 1, 1)));

        assertThat(response.runsDeleted()).isEqualTo(4);
        assertThat(response.restoresDeleted()).isEqualTo(2);
        org.mockito.ArgumentCaptor<OffsetDateTime> captor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(executionService).purgeHistoryBefore(captor.capture());
        assertThat(captor.getValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    // -------------------------------------------------------
    // DELETE /api/v1/admin/backup/runs/{id} (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void deleteRun_eligible_returns204() throws BackupException {
        var response = controller.deleteRun(5L, false);

        verify(executionService).deleteRun(5L);
        verify(executionService, never()).deleteRunCascade(any());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteRun_stillReferenced_returns409() throws BackupException {
        org.mockito.Mockito.doThrow(new BackupException(
                        "Cette sauvegarde est encore référencée par une restauration conservée.",
                        BackupException.Reason.CONFLICT))
                .when(executionService).deleteRun(5L);

        var response = controller.deleteRun(5L, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deleteRun_unknown_returns404() throws BackupException {
        org.mockito.Mockito.doThrow(new BackupException(
                        "Sauvegarde introuvable.", BackupException.Reason.FILE_NOT_FOUND))
                .when(executionService).deleteRun(999L);

        var response = controller.deleteRun(999L, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRun_cascadeTrue_callsDeleteRunCascade() throws BackupException {
        var response = controller.deleteRun(5L, true);

        verify(executionService).deleteRunCascade(5L);
        verify(executionService, never()).deleteRun(any());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // -------------------------------------------------------
    // GET /api/v1/admin/backup/runs/{id}/linked-restores (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void linkedRestores_mapsListOfRestoresToResponses() {
        BackupRestore restore = new BackupRestore();
        restore.setId(1L);
        restore.setBackupRunId(5L);
        restore.setStatus(BackupRestore.STATUS_SUCCESS);
        restore.setStartedAt(OffsetDateTime.now());
        restore.setPerformedBy("admin");
        when(executionService.findLinkedRestores(5L)).thenReturn(List.of(restore));
        when(backupRunRepository.findById(5L)).thenReturn(Optional.of(sampleRun(5L, BackupRun.STATUS_SUCCESS)));

        var result = controller.linkedRestores(5L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
    }

    @Test
    void linkedRestores_none_returnsEmptyList() {
        when(executionService.findLinkedRestores(5L)).thenReturn(List.of());

        var result = controller.linkedRestores(5L);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------
    // GET /api/v1/admin/backup/restores
    // -------------------------------------------------------

    @Test
    void listRestores_mapsPageOfRestoresToResponses() {
        BackupRestore restore = new BackupRestore();
        restore.setId(1L);
        restore.setBackupRunId(9L);
        restore.setStatus(BackupRestore.STATUS_SUCCESS);
        restore.setStartedAt(OffsetDateTime.now());
        restore.setPerformedBy("admin");
        when(backupRestoreRepository.findAllByOrderByStartedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(restore)));
        when(backupRunRepository.findById(9L)).thenReturn(Optional.of(sampleRun(9L, BackupRun.STATUS_SUCCESS)));

        Page<?> page = controller.listRestores(0, 20);

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    // -------------------------------------------------------
    // POST /api/v1/admin/backup/restore
    // -------------------------------------------------------

    @Test
    void restore_success_returns200() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        BackupRestore restoreLog = new BackupRestore();
        restoreLog.setId(1L);
        restoreLog.setBackupRunId(5L);
        restoreLog.setStatus(BackupRestore.STATUS_SUCCESS);
        when(executionService.restore(5L, "subnetory-x.dump", "admin")).thenReturn(restoreLog);
        when(backupRunRepository.findById(5L)).thenReturn(Optional.of(sampleRun(5L, BackupRun.STATUS_SUCCESS)));

        var response = controller.restore(new BackupRestoreRequest(5L, "subnetory-x.dump"), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void restore_confirmationMismatch_returns409() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        when(executionService.restore(5L, "wrong.dump", "admin")).thenThrow(new BackupException(
                "Le texte de confirmation ne correspond pas exactement au nom du fichier de sauvegarde.",
                BackupException.Reason.CONFIRMATION_MISMATCH));

        var response = controller.restore(new BackupRestoreRequest(5L, "wrong.dump"), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void restore_fileNotFound_returns404() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        when(executionService.restore(999L, "anything.dump", "admin")).thenThrow(new BackupException(
                "Sauvegarde introuvable.", BackupException.Reason.FILE_NOT_FOUND));

        var response = controller.restore(new BackupRestoreRequest(999L, "anything.dump"), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void restore_timeout_returns504() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        when(executionService.restore(5L, "x.dump", "admin")).thenThrow(new BackupException(
                "pg_restore a dépassé le délai.", BackupException.Reason.TIMEOUT));

        var response = controller.restore(new BackupRestoreRequest(5L, "x.dump"), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void restore_safetyBackupFailed_returns500() throws BackupException {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("admin");
        when(executionService.restore(5L, "x.dump", "admin")).thenThrow(new BackupException(
                "Restauration annulee : la sauvegarde de securite prealable a echoue.",
                BackupException.Reason.SAFETY_BACKUP_FAILED));

        var response = controller.restore(new BackupRestoreRequest(5L, "x.dump"), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // -------------------------------------------------------
    // GET /api/v1/admin/backup/runs/{id}/download
    // -------------------------------------------------------

    @Test
    void download_existingFile_returnsOctetStreamWithAttachmentHeader() throws Exception {
        Path file = tempDir.resolve("subnetory-20260731-020000.dump");
        writeFile(file, "dump-bytes");
        when(executionService.resolveDownloadableFile(5L)).thenReturn(file);

        var response = controller.download(5L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("subnetory-20260731-020000.dump");
    }

    @Test
    void download_missingFile_returns404() throws Exception {
        when(executionService.resolveDownloadableFile(999L)).thenThrow(new BackupException(
                "Sauvegarde introuvable.", BackupException.Reason.FILE_NOT_FOUND));

        var response = controller.download(999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private BackupRun sampleRun(Long id, String status) {
        BackupRun run = new BackupRun();
        run.setId(id);
        run.setStatus(status);
        run.setTriggerSource(BackupRun.TRIGGER_MANUAL);
        run.setStartedAt(OffsetDateTime.now().minusMinutes(10));
        run.setFinishedAt(OffsetDateTime.now().minusMinutes(9));
        run.setFileName("subnetory-x.dump");
        run.setFileSizeBytes(2048L);
        run.setChecksumSha256("abc123");
        return run;
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
