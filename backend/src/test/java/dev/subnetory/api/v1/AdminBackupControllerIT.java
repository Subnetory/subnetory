package dev.subnetory.api.v1;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.domain.BackupRestore;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.dto.TokenResponse;
import dev.subnetory.repository.BackupRestoreRepository;
import dev.subnetory.repository.BackupRunRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration AdminBackupController (Phase 7 audit, 31/07/2026).
 *
 * <p>pg_dump n'est pas installé dans l'environnement Testcontainers, exactement
 * comme nmap pour {@link ScanControllerIT} ("Fix 3"). Le chemin est donc rendu
 * volontairement invalide via {@code @DynamicPropertySource} pour couvrir de
 * façon déterministe, indépendamment du runner CI, le chemin d'échec
 * {@code TOOL_NOT_AVAILABLE} — de bout en bout, à travers le vrai
 * {@link dev.subnetory.backup.BackupExecutionService} (ProcessBuilder réel),
 * pas un mock. Le reste des scénarios (validation, mapping DTO, statuts HTTP
 * par type d'erreur) est déjà couvert par le test unitaire plus rapide
 * {@link AdminBackupControllerTest}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminBackupControllerIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("subnetory_test")
            .withUsername("subnetory")
            .withPassword("subnetory");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // pg_dump absent dans Testcontainers -> chemin invalide volontaire,
        // meme logique que "Fix 3" de ScanControllerIT pour nmap. pg_restore
        // aussi : les runners GitHub Actions embarquent postgresql-client
        // (donc pg_restore) par defaut, contrairement a l'hypothese initiale
        // "absent du CI" - sans cette ligne, importBackup_pgRestoreNotInstalled_returns503
        // reçoit un vrai pg_restore qui rejette le faux dump avec
        // EXECUTION_FAILED (500) au lieu de TOOL_NOT_AVAILABLE (503) attendu
        // (regression constatee en CI le 01/08/2026).
        registry.add("subnetory.backup.pg-dump-path",
                () -> "__subnetory_pg_dump_not_found__");
        registry.add("subnetory.backup.pg-restore-path",
                () -> "__subnetory_pg_restore_not_found__");
        // Le chemin par defaut (/var/subnetory/backups, cf. application.yml)
        // n'est ecrivable qu'a l'interieur de l'image Docker (creee et
        // chownee au build - voir Dockerfile). Sur un runner CI classique
        // (mvn verify hors conteneur), /var appartient a root : rediriger
        // vers un repertoire temporaire garanti inscriptible, sans quoi
        // ensureStorageDir() echoue AVANT d'atteindre le pg_dump manquant
        // et le test obtiendrait EXECUTION_FAILED (500) au lieu de
        // TOOL_NOT_AVAILABLE (503).
        registry.add("subnetory.backup.storage-path",
                () -> System.getProperty("java.io.tmpdir") + "/subnetory-backup-it-"
                        + System.nanoTime());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired BackupRunRepository backupRunRepository;
    @Autowired BackupRestoreRepository backupRestoreRepository;

    static String adminToken;

    @Test @Order(1) @DisplayName("Setup — authentification admin")
    void setup() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new TokenRequest("admin", "admin"))))
                .andExpect(status().isOk()).andReturn();
        adminToken = om.readValue(
                r.getResponse().getContentAsString(), TokenResponse.class).accessToken();
    }

    // -------------------------------------------------------
    // Sécurité
    // -------------------------------------------------------

    @Test @Order(2) @DisplayName("GET /admin/backup — sans token → 401")
    void getSettings_noAuth_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/backup"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------
    // Configuration
    // -------------------------------------------------------

    @Test @Order(3) @DisplayName("GET /admin/backup — état par défaut (aucune ligne en base)")
    void getSettings_defaultsFromApplicationYml() throws Exception {
        mvc.perform(get("/api/v1/admin/backup")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.retentionCount").value(14))
                .andExpect(jsonPath("$.lastRun").doesNotExist());
    }

    @Test @Order(4) @DisplayName("PUT /admin/backup — cron invalide → 400")
    void updateSettings_invalidCron_returns400() throws Exception {
        mvc.perform(put("/api/v1/admin/backup")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"cronExpression\":\"not a cron\",\"retentionCount\":14}"))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(5) @DisplayName("PUT /admin/backup — configuration valide → 200 et persistée")
    void updateSettings_valid_returns200AndPersists() throws Exception {
        mvc.perform(put("/api/v1/admin/backup")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"cronExpression\":\"0 30 3 * * *\",\"retentionCount\":21}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cronExpression").value("0 30 3 * * *"))
                .andExpect(jsonPath("$.retentionCount").value(21));

        mvc.perform(get("/api/v1/admin/backup")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cronExpression").value("0 30 3 * * *"));
    }

    // -------------------------------------------------------
    // 503 — pg_dump absent (attendu en CI / Testcontainers)
    // -------------------------------------------------------

    @Test @Order(6) @DisplayName("POST /admin/backup/trigger — pg_dump absent → 503 avec message clair")
    void trigger_pgDumpNotInstalled_returns503() throws Exception {
        mvc.perform(post("/api/v1/admin/backup/trigger")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Backup Operation Failed"))
                .andExpect(jsonPath("$.detail", containsString("pg_dump")));
    }

    @Test @Order(7) @DisplayName("GET /admin/backup/runs — l'échec précédent apparaît dans l'historique")
    void listRuns_includesFailedTriggerFromPreviousTest() throws Exception {
        mvc.perform(get("/api/v1/admin/backup/runs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].triggerSource").value("MANUAL"));
    }

    // -------------------------------------------------------
    // Restauration — sauvegarde/fichier inexistant
    // -------------------------------------------------------

    @Test @Order(8) @DisplayName("POST /admin/backup/restore — sauvegarde inexistante → 404")
    void restore_unknownBackupRun_returns404() throws Exception {
        mvc.perform(post("/api/v1/admin/backup/restore")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"backupRunId\":999999,\"confirmationText\":\"anything.dump\"}"))
                .andExpect(status().isNotFound());
    }

    @Test @Order(9) @DisplayName("GET /admin/backup/runs/{id}/download — id inexistant → 404")
    void download_unknownRun_returns404() throws Exception {
        mvc.perform(get("/api/v1/admin/backup/runs/999999/download")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------
    // Import (audit 01/08/2026) — pg_restore absent en CI, meme raison que
    // pg_dump ci-dessus : couvre le chemin d'echec propre TOOL_NOT_AVAILABLE
    // via pg_restore --list, sans dependre d'un binaire reel.
    // -------------------------------------------------------

    @Test @Order(10) @DisplayName("POST /admin/backup/import — pg_restore absent → 503, aucune ligne créée")
    void importBackup_pgRestoreNotInstalled_returns503() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "external.dump", "application/octet-stream", "not a real dump".getBytes());

        mvc.perform(multipart("/api/v1/admin/backup/import")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("pg_restore")));
    }

    // -------------------------------------------------------
    // Purge manuelle (audit 01/08/2026) — pure suppression en base, aucune
    // dépendance à pg_dump/pg_restore, s'exécute pleinement en CI.
    // -------------------------------------------------------

    @Test @Order(11) @DisplayName("POST /admin/backup/purge — coupure future → supprime l'échec créé à l'Order(6)")
    void purge_futureCutoff_deletesExistingFailedRun() throws Exception {
        String tomorrow = java.time.LocalDate.now().plusDays(1).toString();

        mvc.perform(post("/api/v1/admin/backup/purge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beforeDate\":\"" + tomorrow + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runsDeleted", greaterThanOrEqualTo(1)));

        mvc.perform(get("/api/v1/admin/backup/runs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // -------------------------------------------------------
    // Suppression fine d'une seule sauvegarde (audit 01/08/2026) — pure
    // suppression en base/fichier, aucune dépendance à pg_dump/pg_restore.
    // Les lignes sont insérées directement via le repository (pg_dump est
    // indisponible en CI, donc impossible de produire une vraie sauvegarde
    // réussie via l'API dans cet environnement).
    // -------------------------------------------------------

    @Test @Order(12) @DisplayName("DELETE /admin/backup/runs/{id} — id inexistant → 404")
    void deleteRun_unknownId_returns404() throws Exception {
        mvc.perform(delete("/api/v1/admin/backup/runs/999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test @Order(13) @DisplayName("DELETE /admin/backup/runs/{id} — encore en cours → 409")
    void deleteRun_running_returns409() throws Exception {
        BackupRun running = new BackupRun();
        running.setTriggerSource(BackupRun.TRIGGER_MANUAL);
        running.setStatus(BackupRun.STATUS_RUNNING);
        running.setStartedAt(OffsetDateTime.now());
        running = backupRunRepository.saveAndFlush(running);

        mvc.perform(delete("/api/v1/admin/backup/runs/" + running.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("en cours")));
    }

    @Test @Order(14) @DisplayName("DELETE /admin/backup/runs/{id} — sauvegarde éligible → 204, supprimée")
    void deleteRun_eligible_returns204AndRemovesRow() throws Exception {
        BackupRun run = new BackupRun();
        run.setTriggerSource(BackupRun.TRIGGER_MANUAL);
        run.setStatus(BackupRun.STATUS_SUCCESS);
        run.setStartedAt(OffsetDateTime.now());
        run.setFinishedAt(OffsetDateTime.now());
        run = backupRunRepository.saveAndFlush(run);
        Long id = run.getId();

        mvc.perform(delete("/api/v1/admin/backup/runs/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(backupRunRepository.findById(id)).isEmpty();
    }

    // -------------------------------------------------------
    // Suppression avec restaurations liées (audit 01/08/2026)
    // -------------------------------------------------------

    @Test @Order(15) @DisplayName("GET .../linked-restores puis DELETE simple → 409, DELETE ?cascade=true → 204")
    void deleteRunCascade_removesLinkedRestoresToo() throws Exception {
        BackupRun run = new BackupRun();
        run.setTriggerSource(BackupRun.TRIGGER_MANUAL);
        run.setStatus(BackupRun.STATUS_SUCCESS);
        run.setStartedAt(OffsetDateTime.now());
        run.setFinishedAt(OffsetDateTime.now());
        run = backupRunRepository.saveAndFlush(run);
        Long id = run.getId();

        BackupRestore restore = new BackupRestore();
        restore.setBackupRunId(id);
        restore.setStatus(BackupRestore.STATUS_SUCCESS);
        restore.setStartedAt(OffsetDateTime.now());
        restore.setFinishedAt(OffsetDateTime.now());
        restore.setPerformedBy("admin");
        restore = backupRestoreRepository.saveAndFlush(restore);
        Long restoreId = restore.getId();

        mvc.perform(get("/api/v1/admin/backup/runs/" + id + "/linked-restores")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(restoreId));

        mvc.perform(delete("/api/v1/admin/backup/runs/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/v1/admin/backup/runs/" + id + "?cascade=true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(backupRunRepository.findById(id)).isEmpty();
        assertThat(backupRestoreRepository.findById(restoreId)).isEmpty();
    }
}
