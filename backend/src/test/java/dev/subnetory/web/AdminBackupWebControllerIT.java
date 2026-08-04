package dev.subnetory.web;

import dev.subnetory.backup.BackupException;
import dev.subnetory.backup.BackupExecutionService;
import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.config.SecurityConfig;
import dev.subnetory.domain.BackupRestore;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.repository.BackupRestoreRepository;
import dev.subnetory.repository.BackupRunRepository;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.service.BackupConfigurationService;
import dev.subnetory.service.BackupConfigurationService.EffectiveBackupSettings;
import dev.subnetory.web.form.BackupSettingsForm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Phase 7 audit, 31/07/2026 — meme pattern de test que
 * {@link AdminWebControllerIT} (slice {@code @WebMvcTest} + {@code SecurityConfig}
 * importee, tous les autres beans mockes).
 */
@WebMvcTest(AdminBackupWebController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class AdminBackupWebControllerIT {

    @Autowired MockMvc mvc;

    @MockitoBean BackupConfigurationService configurationService;
    @MockitoBean BackupExecutionService executionService;
    @MockitoBean BackupRunRepository backupRunRepository;
    @MockitoBean BackupRestoreRepository backupRestoreRepository;

    // Beans requis par SecurityConfig, non utilises par ce controleur —
    // meme liste que AdminWebControllerIT.
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean SubnetoryUserDetailsService userDetailsService;
    @MockitoBean LoginRateLimiter loginRateLimiter;
    @MockitoBean ApiRateLimiter apiRateLimiter;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean RateLimitingAuthenticationFailureHandler failureHandler;
    @MockitoBean RateLimitingAuthenticationSuccessHandler successHandler;
    // Correctif securite MOYENNE (audit 04/08/2026) : RestoreMaintenanceFilter,
    // cable dans SecurityConfig#webFilterChain, a besoin de ce bean.
    @MockitoBean RestoreMaintenanceGate restoreMaintenanceGate;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        when(configurationService.form()).thenReturn(new BackupSettingsForm());
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(false, "0 0 2 * * *", 14));
        when(configurationService.nextRunAt(any())).thenReturn(null);
        when(backupRunRepository.findAllByOrderByStartedAtDesc(any())).thenReturn(Page.empty());
        when(backupRestoreRepository.findAllByOrderByStartedAtDesc(any())).thenReturn(Page.empty());
        when(backupRunRepository.findFirstByStatusOrderByStartedAtDesc(anyString())).thenReturn(Optional.empty());
        when(backupRunRepository.countByStatus(anyString())).thenReturn(0L);
        when(executionService.totalStorageBytes()).thenReturn(0L);
        when(executionService.storagePath()).thenReturn("/var/subnetory/backups");
        when(executionService.isOperationInProgress()).thenReturn(false);
    }

    // -------------------------------------------------------
    // Accès selon rôle
    // -------------------------------------------------------

    @Test
    void anonymous_dashboard_redirectsToLogin() throws Exception {
        mvc.perform(get("/admin/backup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void roleIp_dashboard_returns403() throws Exception {
        mvc.perform(get("/admin/backup"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleAdmin_dashboard_returns200() throws Exception {
        mvc.perform(get("/admin/backup"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/backup"))
                .andExpect(content().string(containsString("Enregistrer la configuration")))
                .andExpect(content().string(containsString("Sauvegarder maintenant")));
    }

    @Test
    @WithMockUser(roles = "BACKUP")
    void roleBackup_dashboard_returns200() throws Exception {
        // Audit 01/08/2026 : ROLE_BACKUP donne acces aux sauvegardes sans le
        // reste de l'administration (cf. DB_PASSWORD_ROTATION_FEASIBILITY.md).
        mvc.perform(get("/admin/backup"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/backup"));
    }

    @Test
    @WithMockUser(roles = "BACKUP")
    void roleBackup_userAdministration_returns403() throws Exception {
        // ROLE_BACKUP ne doit donner acces qu'aux sauvegardes, jamais au
        // reste de l'administration.
        mvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------
    // Configuration
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSettings_withCsrf_redirectsWithFlashSuccess() throws Exception {
        mvc.perform(post("/admin/backup/settings")
                        .with(csrf())
                        .param("enabled", "true")
                        .param("cronExpression", "0 0 2 * * *")
                        .param("retentionCount", "14"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(configurationService).save(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSettings_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/backup/settings")
                        .param("enabled", "true")
                        .param("cronExpression", "0 0 2 * * *")
                        .param("retentionCount", "14"))
                .andExpect(status().isForbidden());

        verify(configurationService, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateSettings_invalidCron_redirectsWithFlashError() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Expression cron invalide."))
                .when(configurationService).save(any());

        mvc.perform(post("/admin/backup/settings")
                        .with(csrf())
                        .param("enabled", "true")
                        .param("cronExpression", "garbage")
                        .param("retentionCount", "14"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // -------------------------------------------------------
    // Déclenchement manuel
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void trigger_withCsrf_redirectsWithFlashSuccess() throws Exception {
        BackupRun run = new BackupRun();
        run.setId(1L);
        run.setFileName("subnetory-20260731-020000.dump");
        run.setFileSizeBytes(1024L);
        when(executionService.triggerManualBackup(eq("admin"), any())).thenReturn(run);

        mvc.perform(post("/admin/backup/trigger").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void trigger_withLabel_passesLabelToService() throws Exception {
        BackupRun run = new BackupRun();
        run.setId(1L);
        run.setFileName("subnetory-20260731-020000.dump");
        run.setFileSizeBytes(1024L);
        when(executionService.triggerManualBackup(eq("admin"), eq("avant migration V19"))).thenReturn(run);

        mvc.perform(post("/admin/backup/trigger").with(csrf()).param("label", "avant migration V19"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(executionService).triggerManualBackup("admin", "avant migration V19");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void trigger_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/backup/trigger"))
                .andExpect(status().isForbidden());

        verify(executionService, never()).triggerManualBackup(anyString(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void trigger_toolNotAvailable_redirectsWithFlashError() throws Exception {
        when(executionService.triggerManualBackup(eq("admin"), any()))
                .thenThrow(new BackupException(
                        "pg_dump n'est pas installé ou introuvable dans le PATH du conteneur.",
                        BackupException.Reason.TOOL_NOT_AVAILABLE));

        mvc.perform(post("/admin/backup/trigger").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // -------------------------------------------------------
    // Import (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void importBackup_withCsrf_redirectsWithFlashSuccess() throws Exception {
        BackupRun run = new BackupRun();
        run.setId(2L);
        run.setFileName("subnetory-import-20260801-000000.dump");
        run.setFileSizeBytes(2048L);
        when(executionService.importBackup(any(), eq("admin"))).thenReturn(run);

        MockMultipartFile file = new MockMultipartFile(
                "file", "external.dump", "application/octet-stream", "dump-bytes".getBytes());

        mvc.perform(multipart("/admin/backup/import").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void importBackup_invalidFormat_redirectsWithFlashError() throws Exception {
        when(executionService.importBackup(any(), eq("admin")))
                .thenThrow(new BackupException(
                        "Le fichier importé ne semble pas être une sauvegarde pg_dump valide.",
                        BackupException.Reason.EXECUTION_FAILED));

        MockMultipartFile file = new MockMultipartFile(
                "file", "not-a-dump.txt", "text/plain", "garbage".getBytes());

        mvc.perform(multipart("/admin/backup/import").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void importBackup_withoutCsrf_returns403() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "external.dump", "application/octet-stream", "dump-bytes".getBytes());

        mvc.perform(multipart("/admin/backup/import").file(file))
                .andExpect(status().isForbidden());

        verify(executionService, never()).importBackup(any(), anyString());
    }

    @Test
    @WithMockUser(roles = "BACKUP")
    void roleBackup_import_returns403() throws Exception {
        // Correctif securite ELEVEE (audit 04/08/2026) : import reserve a
        // ROLE_ADMIN, un compte ROLE_BACKUP seul (qui passe pourtant la
        // regle d'URL /admin/backup/** hasAnyRole('ADMIN','BACKUP')) doit
        // etre bloque par le @PreAuthorize("hasRole('ADMIN')") de la methode.
        MockMultipartFile file = new MockMultipartFile(
                "file", "external.dump", "application/octet-stream", "dump-bytes".getBytes());

        mvc.perform(multipart("/admin/backup/import").file(file).with(csrf()))
                .andExpect(status().isForbidden());

        verify(executionService, never()).importBackup(any(), anyString());
    }

    // -------------------------------------------------------
    // Purge manuelle (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void purge_withCsrf_redirectsWithFlashSuccess() throws Exception {
        when(executionService.purgeHistoryBefore(any()))
                .thenReturn(new BackupExecutionService.PurgeResult(3, 1));

        mvc.perform(post("/admin/backup/purge").with(csrf()).param("beforeDate", "2026-01-01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void purge_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/backup/purge").param("beforeDate", "2026-01-01"))
                .andExpect(status().isForbidden());

        verify(executionService, never()).purgeHistoryBefore(any());
    }

    // -------------------------------------------------------
    // Suppression fine d'une seule sauvegarde (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRun_withCsrf_redirectsWithFlashSuccess() throws Exception {
        mvc.perform(post("/admin/backup/runs/5/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(executionService).deleteRun(5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRun_conflict_redirectsWithFlashError() throws Exception {
        org.mockito.Mockito.doThrow(new BackupException(
                        "Cette sauvegarde est encore référencée par une restauration conservée.",
                        BackupException.Reason.CONFLICT))
                .when(executionService).deleteRun(5L);

        mvc.perform(post("/admin/backup/runs/5/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRun_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/backup/runs/5/delete"))
                .andExpect(status().isForbidden());

        verify(executionService, never()).deleteRun(anyLong());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteRun_cascadeTrue_callsDeleteRunCascade() throws Exception {
        mvc.perform(post("/admin/backup/runs/5/delete").with(csrf()).param("cascade", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(executionService).deleteRunCascade(5L);
        verify(executionService, never()).deleteRun(anyLong());
    }

    // -------------------------------------------------------
    // Confirmation de suppression (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteConfirm_noLinkedRestores_returns200() throws Exception {
        BackupRun run = sampleRun(5L);
        when(backupRunRepository.findById(5L)).thenReturn(Optional.of(run));
        when(executionService.findLinkedRestores(5L)).thenReturn(java.util.List.of());

        mvc.perform(get("/admin/backup/runs/5/delete-confirm"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/backup-delete-confirm"))
                .andExpect(content().string(containsString("Supprimer définitivement")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteConfirm_withLinkedRestores_listsThem() throws Exception {
        BackupRun run = sampleRun(5L);
        when(backupRunRepository.findById(5L)).thenReturn(Optional.of(run));
        BackupRestore linked = new BackupRestore();
        linked.setId(1L);
        linked.setBackupRunId(5L);
        linked.setStatus(BackupRestore.STATUS_SUCCESS);
        linked.setStartedAt(OffsetDateTime.now());
        linked.setPerformedBy("admin");
        when(executionService.findLinkedRestores(5L)).thenReturn(java.util.List.of(linked));

        mvc.perform(get("/admin/backup/runs/5/delete-confirm"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/backup-delete-confirm"))
                .andExpect(content().string(containsString("Supprimer avec les restaurations liées")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteConfirm_unknownRun_returns404() throws Exception {
        when(backupRunRepository.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/admin/backup/runs/999/delete-confirm"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    // -------------------------------------------------------
    // Confirmation de restauration
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void restoreConfirm_existingRun_returns200() throws Exception {
        BackupRun run = sampleRun(5L);
        when(backupRunRepository.findById(5L)).thenReturn(Optional.of(run));
        when(executionService.isFileAvailable(run)).thenReturn(true);

        mvc.perform(get("/admin/backup/runs/5/restore-confirm"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/backup-restore-confirm"))
                .andExpect(content().string(containsString("Confirmer la restauration")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void restoreConfirm_unknownRun_returns404() throws Exception {
        when(backupRunRepository.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/admin/backup/runs/999/restore-confirm"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void restore_confirmationMismatch_reshowsConfirmPageWithError() throws Exception {
        BackupRun run = sampleRun(5L);
        when(backupRunRepository.findById(5L)).thenReturn(Optional.of(run));
        when(executionService.isFileAvailable(run)).thenReturn(true);
        when(executionService.restore(eq(5L), eq("wrong-name.dump"), eq("admin")))
                .thenThrow(new BackupException(
                        "Le texte de confirmation ne correspond pas exactement au nom du fichier de sauvegarde.",
                        BackupException.Reason.CONFIRMATION_MISMATCH));

        mvc.perform(post("/admin/backup/runs/5/restore")
                        .with(csrf())
                        .param("confirmationText", "wrong-name.dump"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/backup-restore-confirm"))
                .andExpect(model().attributeExists("confirmError"));
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void restore_withCsrfAndCorrectConfirmation_redirectsWithFlashSuccess() throws Exception {
        BackupRestore restoreLog = new BackupRestore();
        restoreLog.setId(1L);
        restoreLog.setBackupRunId(5L);
        when(executionService.restore(eq(5L), eq("subnetory-20260731-020000.dump"), eq("admin")))
                .thenReturn(restoreLog);

        mvc.perform(post("/admin/backup/runs/5/restore")
                        .with(csrf())
                        .param("confirmationText", "subnetory-20260731-020000.dump"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backup"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void restore_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/backup/runs/5/restore")
                        .param("confirmationText", "anything"))
                .andExpect(status().isForbidden());

        verify(executionService, never()).restore(anyLong(), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "BACKUP")
    void roleBackup_restoreConfirm_returns403() throws Exception {
        // Correctif securite ELEVEE (audit 04/08/2026), meme motif que
        // roleBackup_import_returns403 ci-dessus.
        mvc.perform(get("/admin/backup/runs/5/restore-confirm"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BACKUP")
    void roleBackup_restore_returns403() throws Exception {
        mvc.perform(post("/admin/backup/runs/5/restore")
                        .with(csrf())
                        .param("confirmationText", "anything"))
                .andExpect(status().isForbidden());

        verify(executionService, never()).restore(anyLong(), anyString(), anyString());
    }

    // -------------------------------------------------------
    // Téléchargement
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void download_unknownRun_returns404() throws Exception {
        when(executionService.resolveDownloadableFile(999L))
                .thenThrow(new BackupException("Sauvegarde introuvable.", BackupException.Reason.FILE_NOT_FOUND));

        mvc.perform(get("/admin/backup/runs/999/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void download_existingRun_returnsAttachment() throws Exception {
        Path dumpFile = tempDir.resolve("subnetory-20260731-020000.dump");
        writeFile(dumpFile, "dump-content");
        when(executionService.resolveDownloadableFile(5L)).thenReturn(dumpFile);

        mvc.perform(get("/admin/backup/runs/5/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("subnetory-20260731-020000.dump")));
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private BackupRun sampleRun(Long id) {
        BackupRun run = new BackupRun();
        run.setId(id);
        run.setStatus(BackupRun.STATUS_SUCCESS);
        run.setStartedAt(OffsetDateTime.now().minusHours(1));
        run.setFinishedAt(OffsetDateTime.now().minusMinutes(55));
        run.setFileName("subnetory-20260731-020000.dump");
        run.setFileSizeBytes(2048L);
        run.setChecksumSha256("abc123");
        return run;
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
