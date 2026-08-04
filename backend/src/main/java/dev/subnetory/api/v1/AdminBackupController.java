package dev.subnetory.api.v1;

import dev.subnetory.backup.BackupException;
import dev.subnetory.backup.BackupExecutionService;
import dev.subnetory.domain.BackupRestore;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.dto.BackupPurgeRequest;
import dev.subnetory.dto.BackupPurgeResponse;
import dev.subnetory.dto.BackupRestoreRequest;
import dev.subnetory.dto.BackupRestoreResponse;
import dev.subnetory.dto.BackupRunResponse;
import dev.subnetory.dto.BackupSettingsRequest;
import dev.subnetory.dto.BackupSettingsResponse;
import dev.subnetory.dto.BackupTriggerRequest;
import dev.subnetory.repository.BackupRestoreRepository;
import dev.subnetory.repository.BackupRunRepository;
import dev.subnetory.service.BackupConfigurationService;
import dev.subnetory.web.form.BackupSettingsForm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * API REST de gestion des sauvegardes (Phase 7 audit, 31/07/2026) — parité
 * complète avec l'interface web {@code /admin/backup}, conformément à
 * l'approche API-first du projet.
 */
@RestController
@RequestMapping("/api/v1/admin/backup")
@PreAuthorize("hasAnyRole('ADMIN', 'BACKUP')")
@Tag(name = "Admin - Sauvegardes", description = "Configuration, déclenchement, historique et restauration")
public class AdminBackupController {

    private final BackupConfigurationService configurationService;
    private final BackupExecutionService executionService;
    private final BackupRunRepository backupRunRepository;
    private final BackupRestoreRepository backupRestoreRepository;

    public AdminBackupController(BackupConfigurationService configurationService,
                                 BackupExecutionService executionService,
                                 BackupRunRepository backupRunRepository,
                                 BackupRestoreRepository backupRestoreRepository) {
        this.configurationService = configurationService;
        this.executionService = executionService;
        this.backupRunRepository = backupRunRepository;
        this.backupRestoreRepository = backupRestoreRepository;
    }

    @GetMapping
    @Operation(summary = "Lire la configuration et l'état courant de la sauvegarde")
    public BackupSettingsResponse getSettings() {
        return toSettingsResponse();
    }

    @PutMapping
    @Operation(summary = "Modifier la configuration de sauvegarde")
    public ResponseEntity<?> updateSettings(@RequestBody BackupSettingsRequest request) {
        try {
            BackupSettingsForm form = new BackupSettingsForm();
            form.setEnabled(request.enabled());
            form.setCronExpression(request.cronExpression());
            form.setRetentionCount(request.retentionCount());
            configurationService.save(form);
            return ResponseEntity.ok(toSettingsResponse());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/runs")
    @Operation(summary = "Lister l'historique des sauvegardes")
    public Page<BackupRunResponse> listRuns(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("startedAt").descending());
        return backupRunRepository.findAllByOrderByStartedAtDesc(pageable).map(this::toResponse);
    }

    @PostMapping("/trigger")
    @Operation(summary = "Déclencher une sauvegarde immédiate",
            description = "Le champ label du corps (optionnel) est un titre/commentaire libre pour retrouver "
                    + "facilement cette sauvegarde dans l'historique.")
    public ResponseEntity<?> trigger(@RequestBody(required = false) BackupTriggerRequest request,
                                     Authentication auth) {
        try {
            String label = request == null ? null : request.label();
            BackupRun run = executionService.triggerManualBackup(auth.getName(), label);
            return ResponseEntity.ok(toResponse(run));
        } catch (BackupException e) {
            return ResponseEntity.status(toStatus(e.getReason())).body(problem(toStatus(e.getReason()), e.getMessage()));
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    // Correctif securite ELEVEE (audit 04/08/2026) : importer un dump
    // externe equivaut a injecter n'importe quel contenu dans la base
    // applicative (utilisateurs, roles, associations de contextes, donnees
    // metier) — pg_restore --list ne valide que la structure du format
    // pg_dump, pas son contenu. La classe reste ouverte a ROLE_BACKUP pour
    // consulter/declencher/telecharger des sauvegardes normales, mais
    // l'import d'un dump arbitraire est reserve a ROLE_ADMIN, jamais a un
    // compte de service limite aux sauvegardes.
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Importer un fichier de sauvegarde (.dump) — réservé à ROLE_ADMIN",
            description = "Ajoute un fichier pg_dump --format=custom externe (par exemple téléchargé "
                    + "précédemment) à l'historique comme une sauvegarde exploitable. Le format est validé "
                    + "(pg_restore --list) avant acceptation, mais pas le contenu : un dump préparé peut "
                    + "modifier utilisateurs, rôles ou données métier lors d'une restauration ultérieure. "
                    + "Réservé à ROLE_ADMIN (correctif sécurité ÉLEVÉE, audit 04/08/2026) — ROLE_BACKUP seul "
                    + "reçoit 403. La restauration se fait ensuite via l'endpoint /restore habituel, avec les "
                    + "mêmes protections.")
    public ResponseEntity<?> importBackup(@RequestPart("file") MultipartFile file, Authentication auth) {
        try {
            BackupRun run = executionService.importBackup(file, auth.getName());
            return ResponseEntity.ok(toResponse(run));
        } catch (BackupException e) {
            var status = toStatus(e.getReason());
            return ResponseEntity.status(status).body(problem(status, e.getMessage()));
        }
    }

    @PostMapping("/purge")
    @Operation(summary = "Purger définitivement l'historique avant une date",
            description = "Supprime les sauvegardes et restaurations strictement antérieures à beforeDate, "
                    + "ainsi que leurs fichiers .dump s'ils sont encore sur disque. Une sauvegarde encore "
                    + "référencée par une restauration conservée (plus récente que la coupure) est protégée "
                    + "et n'est jamais supprimée, même si elle est elle-même antérieure à la date.")
    public BackupPurgeResponse purge(@RequestBody BackupPurgeRequest request) {
        OffsetDateTime cutoff = request.beforeDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        var result = executionService.purgeHistoryBefore(cutoff);
        return new BackupPurgeResponse(result.runsDeleted(), result.restoresDeleted());
    }

    @GetMapping("/runs/{id}/linked-restores")
    @Operation(summary = "Lister les restaurations encore liées à une sauvegarde",
            description = "Restaurations référençant cette sauvegarde comme source restaurée ou comme "
                    + "sauvegarde de sécurité pré-restauration — à consulter avant un DELETE ?cascade=true "
                    + "pour savoir précisément ce qui serait supprimé avec elle.")
    public java.util.List<BackupRestoreResponse> linkedRestores(@PathVariable Long id) {
        return executionService.findLinkedRestores(id).stream().map(this::toResponse).toList();
    }

    @DeleteMapping("/runs/{id}")
    @Operation(summary = "Supprimer une seule sauvegarde de l'historique",
            description = "Suppression fine, ligne par ligne (contrairement à /purge, en masse avant une "
                    + "date). Supprime le fichier .dump s'il est encore sur disque. Refusée (409) si la "
                    + "sauvegarde est encore référencée par une restauration conservée dans l'historique, ou "
                    + "si elle est encore en cours (RUNNING) — sauf avec cascade=true, qui supprime aussi les "
                    + "restaurations liées (voir GET .../linked-restores pour savoir lesquelles au préalable).")
    public ResponseEntity<?> deleteRun(@PathVariable Long id,
                                       @RequestParam(defaultValue = "false") boolean cascade) {
        try {
            if (cascade) {
                executionService.deleteRunCascade(id);
            } else {
                executionService.deleteRun(id);
            }
            return ResponseEntity.noContent().build();
        } catch (BackupException e) {
            var status = toStatus(e.getReason());
            return ResponseEntity.status(status).body(problem(status, e.getMessage()));
        }
    }

    @GetMapping("/runs/{id}/download")
    @Operation(summary = "Télécharger le fichier d'une sauvegarde")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        try {
            java.nio.file.Path file = executionService.resolveDownloadableFile(id);
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(file.getFileName().toString()).build().toString())
                    .body(resource);
        } catch (BackupException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/restores")
    @Operation(summary = "Lister l'historique des restaurations")
    public Page<BackupRestoreResponse> listRestores(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return backupRestoreRepository.findAllByOrderByStartedAtDesc(pageable).map(this::toResponse);
    }

    @PostMapping("/restore")
    // Correctif securite ELEVEE (audit 04/08/2026) : ecrase l'integralite de
    // la base applicative (utilisateurs, roles, donnees metier) avec le
    // contenu d'un dump — y compris un dump importe via /import ci-dessus.
    // Reserve a ROLE_ADMIN, jamais delegue a un compte de service ROLE_BACKUP.
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Restaurer la base à partir d'une sauvegarde — réservé à ROLE_ADMIN "
            + "(nécessite confirmationText = nom exact du fichier — jamais un simple clic)",
            description = "Réservé à ROLE_ADMIN (correctif sécurité ÉLEVÉE, audit 04/08/2026) : ROLE_BACKUP "
                    + "seul reçoit désormais 403. Restaurer écrase l'intégralité de la base applicative avec "
                    + "le contenu du dump choisi.")
    public ResponseEntity<?> restore(@RequestBody BackupRestoreRequest request, Authentication auth) {
        try {
            BackupRestore result = executionService.restore(
                    request.backupRunId(), request.confirmationText(), auth.getName());
            return ResponseEntity.ok(toResponse(result));
        } catch (BackupException e) {
            var status = toStatus(e.getReason());
            return ResponseEntity.status(status).body(problem(status, e.getMessage()));
        }
    }

    // -------------------------------------------------------
    // Mapping
    // -------------------------------------------------------

    private BackupSettingsResponse toSettingsResponse() {
        var settings = configurationService.effectiveSettings();
        var lastRun = backupRunRepository.findFirstByStatusOrderByStartedAtDesc(BackupRun.STATUS_SUCCESS)
                .map(this::toResponse).orElse(null);
        return new BackupSettingsResponse(
                settings.enabled(),
                settings.cronExpression(),
                settings.retentionCount(),
                executionService.storagePath(),
                configurationService.nextRunAt(OffsetDateTime.now()),
                lastRun,
                backupRunRepository.countByStatus(BackupRun.STATUS_SUCCESS),
                executionService.totalStorageBytes(),
                executionService.isEncryptionEnabled());
    }

    private BackupRunResponse toResponse(BackupRun run) {
        return new BackupRunResponse(
                run.getId(),
                run.getTriggerSource(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getFileName(),
                run.getFileSizeBytes(),
                run.getChecksumSha256(),
                run.getErrorMessage(),
                run.getTriggeredBy(),
                run.getLabel(),
                run.isEncrypted());
    }

    private BackupRestoreResponse toResponse(BackupRestore restore) {
        String fileName = backupRunRepository.findById(restore.getBackupRunId())
                .map(BackupRun::getFileName).orElse(null);
        return new BackupRestoreResponse(
                restore.getId(),
                restore.getBackupRunId(),
                fileName,
                restore.getSafetyBackupRunId(),
                restore.getStatus(),
                restore.getStartedAt(),
                restore.getFinishedAt(),
                restore.getErrorMessage(),
                restore.getPerformedBy());
    }

    private HttpStatus toStatus(BackupException.Reason reason) {
        return switch (reason) {
            case TOOL_NOT_AVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONFIRMATION_MISMATCH -> HttpStatus.CONFLICT;
            case FILE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case SAFETY_BACKUP_FAILED, EXECUTION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle("Backup Operation Failed");
        pd.setProperty("timestamp", OffsetDateTime.now());
        return pd;
    }
}
