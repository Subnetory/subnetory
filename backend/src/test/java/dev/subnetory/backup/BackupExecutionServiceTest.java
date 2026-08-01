package dev.subnetory.backup;

import dev.subnetory.domain.BackupRestore;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.repository.BackupRestoreRepository;
import dev.subnetory.repository.BackupRunRepository;
import dev.subnetory.service.BackupConfigurationService;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression de deux bugs lies, corriges le 31/07/2026 :
 *
 * <ol>
 *   <li>{@code POST /admin/backup/runs/{id}/restore} renvoyait 500
 *       ({@code org.hibernate.StaleObjectStateException} sur BackupRestore)
 *       parce que {@code pg_dump} incluait les tables {@code backup_runs} /
 *       {@code backup_restores} / {@code backup_settings} dans le dump. Une
 *       restauration ({@code pg_restore --clean}) droppait et recreait
 *       ensuite ces tables avec l'ancien contenu du dump PENDANT que
 *       l'application, connectee a la meme base, ecrivait dans ces memes
 *       tables pour tracer l'operation en cours — la ligne fraichement
 *       inseree disparaissait sous les pieds de Hibernate. Consequence
 *       visible independamment du crash : chaque dump contenait aussi sa
 *       propre ligne backup_runs encore au statut RUNNING (pris avant la
 *       mise a jour finale), affichee comme bloquee "En cours" apres
 *       restauration. Corrige en excluant ces tables du dump
 *       ({@link BackupExecutionService#BACKUP_METADATA_TABLES}).</li>
 *   <li>Une fois (1) corrige, la restauration echouait encore avec
 *       {@code pg_restore: error: could not execute query: ... cannot drop
 *       sequence public.backup_runs_id_seq because other objects depend on
 *       it}. Cause : {@code --exclude-table} exclut la table mais pas la
 *       sequence BIGSERIAL qui alimente sa colonne {@code id} — {@code
 *       pg_dump} emettait quand meme {@code DROP SEQUENCE IF EXISTS
 *       backup_runs_id_seq;}, qui echouait car la table {@code backup_runs}
 *       (non touchee par cette restauration) referencait toujours sa
 *       sequence. Corrige en excluant aussi explicitement les sequences
 *       ({@link BackupExecutionService#BACKUP_METADATA_SEQUENCES}).</li>
 *   <li>Meme apres (2), une restauration anterieure (echouee a cause de (2))
 *       avait laisse une sequence desynchronisee : {@code pg_restore}, sans
 *       {@code --exit-on-error}, continue par defaut d'executer le reste de
 *       l'archive apres une erreur — le {@code DROP SEQUENCE} avait echoue,
 *       mais un {@code SELECT pg_catalog.setval(...)} plus loin dans le
 *       meme dump s'etait quand meme execute, remettant le compteur de
 *       {@code backup_runs_id_seq} a sa valeur au moment du dump. Consequence
 *       differee : {@code ConstraintViolationException} (cle dupliquee sur
 *       {@code backup_runs_pkey}) des la sauvegarde manuelle suivante.
 *       Corrige en ajoutant {@code --exit-on-error} a pg_restore
 *       ({@link BackupExecutionService#buildPgRestoreCommand}) : une
 *       restauration qui echoue s'arrete net, sans effet de bord partiel.</li>
 * </ol>
 *
 * <p>Ce test ne necessite ni pg_dump reel ni conteneur PostgreSQL — il
 * inspecte uniquement la commande construite par
 * {@link BackupExecutionService#buildPgDumpCommand}, extraite de
 * {@code runPgDump} pour rester testable sans dependance externe (pg_dump /
 * pg_restore sont indisponibles dans l'environnement Testcontainers de CI,
 * voir {@code AdminBackupControllerIT}). Toute regression qui retirerait
 * l'exclusion de ces tables ou sequences fait echouer ce test immediatement,
 * sans avoir besoin de reproduire manuellement une restauration reelle.</p>
 */
class BackupExecutionServiceTest {

    private final BackupRunRepository backupRunRepository = mock(BackupRunRepository.class);
    private final BackupRestoreRepository backupRestoreRepository = mock(BackupRestoreRepository.class);
    private final BackupConfigurationService configurationService = mock(BackupConfigurationService.class);
    private final dev.subnetory.service.AuthAuditService authAuditService =
            mock(dev.subnetory.service.AuthAuditService.class);
    private final BackupExecutionService service =
            new BackupExecutionService(backupRunRepository, backupRestoreRepository, configurationService, authAuditService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "pgDumpPath", "pg_dump");
        ReflectionTestUtils.setField(service, "pgRestorePath", "pg_restore");
        ReflectionTestUtils.setField(service, "dbUser", "subnetory");
        ReflectionTestUtils.setField(service, "dbPassword", "secret");
    }

    @Test
    void backupMetadataTables_areExactlyTheThreeBackupTables() {
        // Verrou explicite sur la liste elle-meme : toute table de
        // sauvegarde ajoutee plus tard (nouvelle migration backup_*) doit
        // etre ajoutee ici consciemment, pas oubliee.
        assertThat(BackupExecutionService.BACKUP_METADATA_TABLES)
                .containsExactlyInAnyOrder("backup_settings", "backup_runs", "backup_restores");
    }

    @Test
    void backupMetadataSequences_areExactlyTheOwnedSequences() {
        // backup_settings.id est un BIGINT fixe (pas de sequence) : seules
        // backup_runs et backup_restores ont une colonne BIGSERIAL.
        assertThat(BackupExecutionService.BACKUP_METADATA_SEQUENCES)
                .containsExactlyInAnyOrder("backup_runs_id_seq", "backup_restores_id_seq");
    }

    @Test
    void buildPgDumpCommand_excludesAllBackupMetadataTables() {
        var conn = new BackupExecutionService.DbConnectionInfo("db", 5432, "subnetory");
        Path target = Path.of("/var/subnetory/backups/subnetory-20260731-020000.dump");

        List<String> command = service.buildPgDumpCommand(conn, target);

        for (String table : BackupExecutionService.BACKUP_METADATA_TABLES) {
            assertThat(command).contains("--exclude-table=" + table);
        }
    }

    @Test
    void buildPgDumpCommand_excludesAllBackupMetadataSequences() {
        var conn = new BackupExecutionService.DbConnectionInfo("db", 5432, "subnetory");
        Path target = Path.of("/var/subnetory/backups/subnetory-20260731-020000.dump");

        List<String> command = service.buildPgDumpCommand(conn, target);

        for (String sequence : BackupExecutionService.BACKUP_METADATA_SEQUENCES) {
            assertThat(command).contains("--exclude-table=" + sequence);
        }
    }

    @Test
    void buildPgDumpCommand_keepsExpectedConnectionAndOutputArguments() {
        var conn = new BackupExecutionService.DbConnectionInfo("db", 5432, "subnetory");
        Path target = Path.of("/var/subnetory/backups/subnetory-20260731-020000.dump");

        List<String> command = service.buildPgDumpCommand(conn, target);

        assertThat(command).contains(
                "pg_dump",
                "--format=custom",
                "--host=db",
                "--port=5432",
                "--username=subnetory",
                "--dbname=subnetory",
                "--file=" + target);
    }

    @Test
    void buildPgRestoreCommand_stopsOnFirstErrorInsteadOfContinuingSilently() {
        var conn = new BackupExecutionService.DbConnectionInfo("db", 5432, "subnetory");
        Path source = Path.of("/var/subnetory/backups/subnetory-20260731-232830.dump");

        List<String> command = service.buildPgRestoreCommand(conn, source);

        // Regression : sans ce flag, un DROP SEQUENCE bloque (cf. tests
        // ci-dessus) n'empeche pas pg_restore d'executer un setval() plus
        // loin dans l'archive, desynchronisant la sequence silencieusement.
        assertThat(command).contains("--exit-on-error");
    }

    @Test
    void buildPgRestoreCommand_keepsExpectedSafetyFlags() {
        var conn = new BackupExecutionService.DbConnectionInfo("db", 5432, "subnetory");
        Path source = Path.of("/var/subnetory/backups/subnetory-20260731-232830.dump");

        List<String> command = service.buildPgRestoreCommand(conn, source);

        assertThat(command).contains(
                "pg_restore",
                "--clean",
                "--if-exists",
                "--no-owner",
                "--host=db",
                "--port=5432",
                "--username=subnetory",
                "--dbname=subnetory",
                source.toString());
    }

    // -------------------------------------------------------
    // Reconciliation au demarrage (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void reconcileOrphanedOperations_marksRunningRunsAndRestoresAsFailed() {
        BackupRun orphanedRun = new BackupRun();
        orphanedRun.setId(42L);
        orphanedRun.setStatus(BackupRun.STATUS_RUNNING);
        orphanedRun.setStartedAt(OffsetDateTime.now().minusHours(1));
        when(backupRunRepository.findByStatus(BackupRun.STATUS_RUNNING)).thenReturn(List.of(orphanedRun));

        BackupRestore orphanedRestore = new BackupRestore();
        orphanedRestore.setId(7L);
        orphanedRestore.setStatus(BackupRestore.STATUS_RUNNING);
        orphanedRestore.setStartedAt(OffsetDateTime.now().minusHours(1));
        when(backupRestoreRepository.findByStatus(BackupRestore.STATUS_RUNNING)).thenReturn(List.of(orphanedRestore));

        service.reconcileOrphanedOperations();

        assertThat(orphanedRun.getStatus()).isEqualTo(BackupRun.STATUS_FAILED);
        assertThat(orphanedRun.getFinishedAt()).isNotNull();
        assertThat(orphanedRun.getErrorMessage()).contains("redémarrage");
        verify(backupRunRepository).save(orphanedRun);

        assertThat(orphanedRestore.getStatus()).isEqualTo(BackupRestore.STATUS_FAILED);
        assertThat(orphanedRestore.getFinishedAt()).isNotNull();
        assertThat(orphanedRestore.getErrorMessage()).contains("redémarrage");
        verify(backupRestoreRepository).save(orphanedRestore);
    }

    @Test
    void reconcileOrphanedOperations_doesNothingWhenNoOrphans() {
        when(backupRunRepository.findByStatus(BackupRun.STATUS_RUNNING)).thenReturn(List.of());
        when(backupRestoreRepository.findByStatus(BackupRestore.STATUS_RUNNING)).thenReturn(List.of());

        service.reconcileOrphanedOperations();

        verify(backupRunRepository, never()).save(any());
        verify(backupRestoreRepository, never()).save(any());
    }

    // -------------------------------------------------------
    // Titre/commentaire (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void triggerManualBackup_normalizesAndPersistsLabelEvenOnFailure(@TempDir Path tempDir) {
        // pg_dump absent -> echec rapide et propre (meme sentinelle que le
        // reste du projet, cf. AdminBackupControllerIT), mais le label doit
        // deja avoir ete assigne et sauvegarde avant cet echec.
        ReflectionTestUtils.setField(service, "pgDumpPath", "__subnetory_pg_dump_not_found__");
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        ReflectionTestUtils.setField(service, "jdbcUrl", "jdbc:postgresql://db:5432/subnetory");
        when(backupRunRepository.saveAndFlush(any(BackupRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(backupRunRepository.save(any(BackupRun.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.triggerManualBackup("admin", "  avant migration V19  "))
                .isInstanceOf(BackupException.class);

        ArgumentCaptor<BackupRun> captor = ArgumentCaptor.forClass(BackupRun.class);
        verify(backupRunRepository, times(1)).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getLabel()).isEqualTo("avant migration V19");
    }

    @Test
    void triggerManualBackup_blankLabel_isStoredAsNull(@TempDir Path tempDir) {
        ReflectionTestUtils.setField(service, "pgDumpPath", "__subnetory_pg_dump_not_found__");
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        ReflectionTestUtils.setField(service, "jdbcUrl", "jdbc:postgresql://db:5432/subnetory");
        when(backupRunRepository.saveAndFlush(any(BackupRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(backupRunRepository.save(any(BackupRun.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.triggerManualBackup("admin", "   "))
                .isInstanceOf(BackupException.class);

        ArgumentCaptor<BackupRun> captor = ArgumentCaptor.forClass(BackupRun.class);
        verify(backupRunRepository, times(1)).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getLabel()).isNull();
    }

    // -------------------------------------------------------
    // Import (audit 01/08/2026) — validations avant tout appel a pg_restore,
    // testables sans binaire ni conteneur (voir AdminBackupControllerIT pour
    // le chemin TOOL_NOT_AVAILABLE de bout en bout).
    // -------------------------------------------------------

    @Test
    void importBackup_emptyFile_isRejectedWithoutTouchingRepository() {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.dump", "application/octet-stream", new byte[0]);

        assertThatThrownBy(() -> service.importBackup(empty, "admin"))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("Aucun fichier");

        verify(backupRunRepository, never()).save(any());
    }

    @Test
    void importBackup_nullFile_isRejected() {
        assertThatThrownBy(() -> service.importBackup(null, "admin"))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("Aucun fichier");
    }

    @Test
    void importBackup_fileLargerThanLimit_isRejectedWithoutTouchingDisk(@TempDir Path tempDir) {
        ReflectionTestUtils.setField(service, "importMaxSizeBytes", 10L);
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        MockMultipartFile tooBig = new MockMultipartFile(
                "file", "big.dump", "application/octet-stream", "this is more than 10 bytes".getBytes());

        assertThatThrownBy(() -> service.importBackup(tooBig, "admin"))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("volumineux");

        verify(backupRunRepository, never()).save(any());
    }

    // -------------------------------------------------------
    // Purge manuelle (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void purgeHistoryBefore_deletesEligibleRestoresAndRuns(@TempDir Path tempDir) {
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        OffsetDateTime cutoff = OffsetDateTime.now();

        BackupRestore oldRestore = new BackupRestore();
        oldRestore.setId(1L);
        oldRestore.setBackupRunId(10L);
        when(backupRestoreRepository.findByStartedAtBeforeAndStatusNot(cutoff, BackupRestore.STATUS_RUNNING))
                .thenReturn(List.of(oldRestore));

        BackupRun oldRun = new BackupRun();
        oldRun.setId(10L);
        oldRun.setStatus(BackupRun.STATUS_SUCCESS);
        when(backupRunRepository.findByStartedAtBeforeAndStatusNot(cutoff, BackupRun.STATUS_RUNNING))
                .thenReturn(List.of(oldRun));
        // Plus aucune restauration ne le reference une fois oldRestore supprime.
        when(backupRestoreRepository.existsByBackupRunIdOrSafetyBackupRunId(10L, 10L)).thenReturn(false);

        BackupExecutionService.PurgeResult result = service.purgeHistoryBefore(cutoff);

        assertThat(result.restoresDeleted()).isEqualTo(1);
        assertThat(result.runsDeleted()).isEqualTo(1);
        verify(backupRestoreRepository).delete(oldRestore);
        verify(backupRunRepository).delete(oldRun);
    }

    @Test
    void purgeHistoryBefore_keepsRunStillReferencedByARemainingRestore(@TempDir Path tempDir) {
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        OffsetDateTime cutoff = OffsetDateTime.now();

        // Aucune restauration a purger, mais backup_runs #10 est toujours
        // reference par une restauration plus recente que la coupure —
        // il ne doit jamais etre supprime malgre son propre age.
        when(backupRestoreRepository.findByStartedAtBeforeAndStatusNot(cutoff, BackupRestore.STATUS_RUNNING))
                .thenReturn(List.of());

        BackupRun stillReferencedRun = new BackupRun();
        stillReferencedRun.setId(10L);
        stillReferencedRun.setStatus(BackupRun.STATUS_SUCCESS);
        when(backupRunRepository.findByStartedAtBeforeAndStatusNot(cutoff, BackupRun.STATUS_RUNNING))
                .thenReturn(List.of(stillReferencedRun));
        when(backupRestoreRepository.existsByBackupRunIdOrSafetyBackupRunId(10L, 10L)).thenReturn(true);

        BackupExecutionService.PurgeResult result = service.purgeHistoryBefore(cutoff);

        assertThat(result.runsDeleted()).isEqualTo(0);
        verify(backupRunRepository, never()).delete(any(BackupRun.class));
    }

    // -------------------------------------------------------
    // Suppression fine d'une seule sauvegarde (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void deleteRun_unknownId_throwsFileNotFound() {
        when(backupRunRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.deleteRun(999L))
                .isInstanceOf(BackupException.class)
                .satisfies(e -> assertThat(((BackupException) e).getReason())
                        .isEqualTo(BackupException.Reason.FILE_NOT_FOUND));

        verify(backupRunRepository, never()).delete(any(BackupRun.class));
    }

    @Test
    void deleteRun_stillRunning_isRefusedWithConflict() {
        BackupRun running = new BackupRun();
        running.setId(5L);
        running.setStatus(BackupRun.STATUS_RUNNING);
        when(backupRunRepository.findById(5L)).thenReturn(java.util.Optional.of(running));

        assertThatThrownBy(() -> service.deleteRun(5L))
                .isInstanceOf(BackupException.class)
                .satisfies(e -> assertThat(((BackupException) e).getReason())
                        .isEqualTo(BackupException.Reason.CONFLICT));

        verify(backupRunRepository, never()).delete(any(BackupRun.class));
    }

    @Test
    void deleteRun_stillReferencedByARestore_isRefusedWithConflict() {
        BackupRun run = new BackupRun();
        run.setId(10L);
        run.setStatus(BackupRun.STATUS_SUCCESS);
        when(backupRunRepository.findById(10L)).thenReturn(java.util.Optional.of(run));
        when(backupRestoreRepository.existsByBackupRunIdOrSafetyBackupRunId(10L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteRun(10L))
                .isInstanceOf(BackupException.class)
                .satisfies(e -> assertThat(((BackupException) e).getReason())
                        .isEqualTo(BackupException.Reason.CONFLICT));

        verify(backupRunRepository, never()).delete(any(BackupRun.class));
    }

    @Test
    void deleteRun_eligible_deletesFileAndRow(@TempDir Path tempDir) throws Exception {
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        Path file = tempDir.resolve("subnetory-20260801-000000.dump");
        java.nio.file.Files.writeString(file, "dump-content");

        BackupRun run = new BackupRun();
        run.setId(20L);
        run.setStatus(BackupRun.STATUS_SUCCESS);
        run.setFileName(file.getFileName().toString());
        when(backupRunRepository.findById(20L)).thenReturn(java.util.Optional.of(run));
        when(backupRestoreRepository.existsByBackupRunIdOrSafetyBackupRunId(20L, 20L)).thenReturn(false);

        service.deleteRun(20L);

        assertThat(java.nio.file.Files.exists(file)).isFalse();
        verify(backupRunRepository).delete(run);
    }

    @Test
    void deleteRun_noFileName_deletesRowWithoutTouchingDisk(@TempDir Path tempDir) throws Exception {
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        BackupRun run = new BackupRun();
        run.setId(21L);
        run.setStatus(BackupRun.STATUS_FAILED);
        run.setFileName(null);
        when(backupRunRepository.findById(21L)).thenReturn(java.util.Optional.of(run));
        when(backupRestoreRepository.existsByBackupRunIdOrSafetyBackupRunId(21L, 21L)).thenReturn(false);

        service.deleteRun(21L);

        verify(backupRunRepository).delete(run);
    }

    // -------------------------------------------------------
    // Suppression avec restaurations liees (audit 01/08/2026)
    // -------------------------------------------------------

    @Test
    void findLinkedRestores_delegatesToRepository() {
        BackupRestore r1 = new BackupRestore();
        r1.setId(1L);
        when(backupRestoreRepository.findByBackupRunIdOrSafetyBackupRunId(30L, 30L))
                .thenReturn(List.of(r1));

        List<BackupRestore> result = service.findLinkedRestores(30L);

        assertThat(result).containsExactly(r1);
    }

    @Test
    void deleteRunCascade_unknownId_throwsFileNotFound() {
        when(backupRunRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.deleteRunCascade(999L))
                .isInstanceOf(BackupException.class)
                .satisfies(e -> assertThat(((BackupException) e).getReason())
                        .isEqualTo(BackupException.Reason.FILE_NOT_FOUND));
    }

    @Test
    void deleteRunCascade_stillRunning_isRefusedWithConflict() {
        BackupRun running = new BackupRun();
        running.setId(5L);
        running.setStatus(BackupRun.STATUS_RUNNING);
        when(backupRunRepository.findById(5L)).thenReturn(java.util.Optional.of(running));

        assertThatThrownBy(() -> service.deleteRunCascade(5L))
                .isInstanceOf(BackupException.class)
                .satisfies(e -> assertThat(((BackupException) e).getReason())
                        .isEqualTo(BackupException.Reason.CONFLICT));

        verify(backupRunRepository, never()).delete(any(BackupRun.class));
    }

    @Test
    void deleteRunCascade_linkedRestoreStillRunning_isRefusedWithConflict() {
        BackupRun run = new BackupRun();
        run.setId(30L);
        run.setStatus(BackupRun.STATUS_SUCCESS);
        when(backupRunRepository.findById(30L)).thenReturn(java.util.Optional.of(run));

        BackupRestore runningRestore = new BackupRestore();
        runningRestore.setId(1L);
        runningRestore.setStatus(BackupRestore.STATUS_RUNNING);
        when(backupRestoreRepository.findByBackupRunIdOrSafetyBackupRunId(30L, 30L))
                .thenReturn(List.of(runningRestore));

        assertThatThrownBy(() -> service.deleteRunCascade(30L))
                .isInstanceOf(BackupException.class)
                .satisfies(e -> assertThat(((BackupException) e).getReason())
                        .isEqualTo(BackupException.Reason.CONFLICT));

        verify(backupRestoreRepository, never()).delete(any(BackupRestore.class));
        verify(backupRunRepository, never()).delete(any(BackupRun.class));
    }

    @Test
    void deleteRunCascade_deletesLinkedRestoresThenTheRun(@TempDir Path tempDir) throws Exception {
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        Path file = tempDir.resolve("subnetory-import-20260801-010817.dump");
        java.nio.file.Files.writeString(file, "dump-content");

        BackupRun run = new BackupRun();
        run.setId(30L);
        run.setStatus(BackupRun.STATUS_SUCCESS);
        run.setFileName(file.getFileName().toString());
        when(backupRunRepository.findById(30L)).thenReturn(java.util.Optional.of(run));

        BackupRestore restore1 = new BackupRestore();
        restore1.setId(1L);
        restore1.setStatus(BackupRestore.STATUS_SUCCESS);
        BackupRestore restore2 = new BackupRestore();
        restore2.setId(2L);
        restore2.setStatus(BackupRestore.STATUS_SUCCESS);
        when(backupRestoreRepository.findByBackupRunIdOrSafetyBackupRunId(30L, 30L))
                .thenReturn(List.of(restore1, restore2));

        service.deleteRunCascade(30L);

        verify(backupRestoreRepository).delete(restore1);
        verify(backupRestoreRepository).delete(restore2);
        verify(backupRunRepository).delete(run);
        assertThat(java.nio.file.Files.exists(file)).isFalse();
    }

    @Test
    void deleteRunCascade_noLinkedRestores_behavesLikeSimpleDelete(@TempDir Path tempDir) throws Exception {
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        BackupRun run = new BackupRun();
        run.setId(31L);
        run.setStatus(BackupRun.STATUS_SUCCESS);
        run.setFileName(null);
        when(backupRunRepository.findById(31L)).thenReturn(java.util.Optional.of(run));
        when(backupRestoreRepository.findByBackupRunIdOrSafetyBackupRunId(31L, 31L))
                .thenReturn(List.of());

        service.deleteRunCascade(31L);

        verify(backupRestoreRepository, never()).delete(any(BackupRestore.class));
        verify(backupRunRepository).delete(run);
    }

    // -------------------------------------------------------
    // Chiffrement des sauvegardes (audit 01/08/2026, backlog #13)
    //
    // encryptFile/decryptFile/isEncryptedFile sont privees : appelees via
    // ReflectionTestUtils.invokeMethod uniquement quand aucune exception
    // n'est attendue (une exception verifiee levee par une methode invoquee
    // ainsi ressort enveloppee dans UndeclaredThrowableException, pas
    // directement en BackupException). Les scenarios de rejet passent donc
    // systematiquement par importBackup (methode publique, exception non
    // enveloppee), qui exerce exactement le meme chemin de dechiffrement en
    // interne.
    // -------------------------------------------------------

    private byte[] encryptFixture(byte[] plaintext, Path tempDir, String key) throws Exception {
        ReflectionTestUtils.setField(service, "encryptionKey", key);
        Path plain = tempDir.resolve("fixture-plain-" + java.util.UUID.randomUUID() + ".dump");
        Path enc = tempDir.resolve("fixture-enc-" + java.util.UUID.randomUUID() + ".dump.enc");
        java.nio.file.Files.write(plain, plaintext);
        ReflectionTestUtils.invokeMethod(service, "encryptFile", plain, enc);
        return java.nio.file.Files.readAllBytes(enc);
    }

    @Test
    void isEncryptionEnabled_falseByDefault() {
        assertThat(service.isEncryptionEnabled()).isFalse();
    }

    @Test
    void isEncryptionEnabled_falseWhenKeyBlank() {
        ReflectionTestUtils.setField(service, "encryptionKey", "   ");
        assertThat(service.isEncryptionEnabled()).isFalse();
    }

    @Test
    void isEncryptionEnabled_trueWhenKeyConfigured() {
        ReflectionTestUtils.setField(service, "encryptionKey", "correct horse battery staple");
        assertThat(service.isEncryptionEnabled()).isTrue();
    }

    @Test
    void encryptFile_then_decryptFile_roundTrip_recoversOriginalBytes(@TempDir Path tempDir) throws Exception {
        ReflectionTestUtils.setField(service, "encryptionKey", "un mot de passe assez long et solide");
        byte[] original = ("contenu de sauvegarde de test, avec des octets varies : "
                + "éèàçù€ — et un peu de longueur pour couvrir plusieurs blocs AES.")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path plain = tempDir.resolve("plain.dump");
        Path enc = tempDir.resolve("plain.dump.enc");
        Path decrypted = tempDir.resolve("decrypted.dump");
        java.nio.file.Files.write(plain, original);

        ReflectionTestUtils.invokeMethod(service, "encryptFile", plain, enc);

        assertThat(java.nio.file.Files.readAllBytes(enc)).isNotEqualTo(original);
        Boolean detected = ReflectionTestUtils.invokeMethod(service, "isEncryptedFile", enc);
        assertThat(detected).isTrue();

        ReflectionTestUtils.invokeMethod(service, "decryptFile", enc, decrypted);

        assertThat(java.nio.file.Files.readAllBytes(decrypted)).isEqualTo(original);
    }

    @Test
    void isEncryptedFile_plainFile_isFalse(@TempDir Path tempDir) throws Exception {
        Path plain = tempDir.resolve("plain.dump");
        java.nio.file.Files.write(plain, "PGDMP-ceci-n-est-pas-notre-en-tete".getBytes());

        Boolean detected = ReflectionTestUtils.invokeMethod(service, "isEncryptedFile", plain);

        assertThat(detected).isFalse();
    }

    @Test
    void importBackup_encryptedFileWithoutKeyConfigured_isRejected(@TempDir Path tempDir) throws Exception {
        byte[] encryptedBytes = encryptFixture(
                "contenu chiffre par une autre instance".getBytes(), tempDir, "cle-instance-distante");
        ReflectionTestUtils.setField(service, "encryptionKey", "");
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        ReflectionTestUtils.setField(service, "importMaxSizeBytes", 10_000_000L);
        MockMultipartFile upload = new MockMultipartFile(
                "file", "backup.dump.enc", "application/octet-stream", encryptedBytes);

        assertThatThrownBy(() -> service.importBackup(upload, "admin"))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("aucune clé de chiffrement");

        verify(backupRunRepository, never()).save(any());
    }

    @Test
    void importBackup_encryptedFileWithWrongKey_isRejectedByHmac(@TempDir Path tempDir) throws Exception {
        byte[] encryptedBytes = encryptFixture("contenu original".getBytes(), tempDir, "cle-correcte");
        ReflectionTestUtils.setField(service, "encryptionKey", "cle-incorrecte");
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        ReflectionTestUtils.setField(service, "importMaxSizeBytes", 10_000_000L);
        MockMultipartFile upload = new MockMultipartFile(
                "file", "backup.dump.enc", "application/octet-stream", encryptedBytes);

        assertThatThrownBy(() -> service.importBackup(upload, "admin"))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("HMAC");

        verify(backupRunRepository, never()).save(any());
    }

    @Test
    void importBackup_encryptedFileTampered_isRejectedByHmac(@TempDir Path tempDir) throws Exception {
        byte[] encryptedBytes = encryptFixture(
                "contenu original assez long pour etre altere quelque part au milieu du fichier".getBytes(),
                tempDir, "meme-cle-des-deux-cotes");
        int tamperIndex = encryptedBytes.length / 2;
        encryptedBytes[tamperIndex] = (byte) (encryptedBytes[tamperIndex] ^ 0xFF);

        ReflectionTestUtils.setField(service, "encryptionKey", "meme-cle-des-deux-cotes");
        ReflectionTestUtils.setField(service, "storagePathStr", tempDir.toString());
        ReflectionTestUtils.setField(service, "importMaxSizeBytes", 10_000_000L);
        MockMultipartFile upload = new MockMultipartFile(
                "file", "backup.dump.enc", "application/octet-stream", encryptedBytes);

        assertThatThrownBy(() -> service.importBackup(upload, "admin"))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("HMAC");

        verify(backupRunRepository, never()).save(any());
    }
}
