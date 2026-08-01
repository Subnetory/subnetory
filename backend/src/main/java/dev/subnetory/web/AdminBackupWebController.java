package dev.subnetory.web;

import dev.subnetory.backup.BackupException;
import dev.subnetory.backup.BackupExecutionService;
import dev.subnetory.domain.BackupRestore;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.BackupRestoreRepository;
import dev.subnetory.repository.BackupRunRepository;
import dev.subnetory.service.BackupConfigurationService;
import dev.subnetory.web.form.BackupSettingsForm;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Interface d'administration des sauvegardes (Phase 7 audit, 31/07/2026).
 *
 * <p>Périmètre : configuration (activation, planification, rétention),
 * déclenchement manuel, historique détaillé, téléchargement, restauration
 * guidée avec confirmation forte — cf. {@link BackupExecutionService}.</p>
 */
@Controller
@RequestMapping("/admin/backup")
@PreAuthorize("hasAnyRole('ADMIN', 'BACKUP')")
public class AdminBackupWebController {

    private static final int HISTORY_PAGE_SIZE = 20;

    private final BackupConfigurationService configurationService;
    private final BackupExecutionService executionService;
    private final BackupRunRepository backupRunRepository;
    private final BackupRestoreRepository backupRestoreRepository;

    public AdminBackupWebController(BackupConfigurationService configurationService,
                                    BackupExecutionService executionService,
                                    BackupRunRepository backupRunRepository,
                                    BackupRestoreRepository backupRestoreRepository) {
        this.configurationService = configurationService;
        this.executionService = executionService;
        this.backupRunRepository = backupRunRepository;
        this.backupRestoreRepository = backupRestoreRepository;
    }

    // ── Tableau de bord ──────────────────────────────────────────────────

    @GetMapping
    public String dashboard(@RequestParam(defaultValue = "0") int page, Model model) {
        prepareDashboardModel(model, page, configurationService.form());
        return "admin/backup";
    }

    @PostMapping("/settings")
    public String updateSettings(@ModelAttribute("backupForm") BackupSettingsForm form,
                                 RedirectAttributes flash) {
        try {
            configurationService.save(form);
            flash.addFlashAttribute("flashSuccess", "Configuration de sauvegarde enregistrée.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/backup";
    }

    // ── Déclenchement manuel ─────────────────────────────────────────────

    @PostMapping("/trigger")
    public String trigger(@RequestParam(required = false) String label,
                          Authentication auth, RedirectAttributes flash) {
        try {
            BackupRun run = executionService.triggerManualBackup(auth.getName(), label);
            flash.addFlashAttribute("flashSuccess",
                    "Sauvegarde terminée : " + run.getFileName()
                            + " (" + formatBytes(run.getFileSizeBytes()) + ").");
        } catch (BackupException e) {
            flash.addFlashAttribute("flashError", "Sauvegarde échouée : " + e.getMessage());
        }
        return "redirect:/admin/backup";
    }

    // ── Import d'une sauvegarde téléchargée ──────────────────────────────

    @PostMapping("/import")
    public String importBackup(@RequestParam("file") MultipartFile file,
                               Authentication auth, RedirectAttributes flash) {
        try {
            BackupRun run = executionService.importBackup(file, auth.getName());
            flash.addFlashAttribute("flashSuccess",
                    "Sauvegarde importée : " + run.getFileName()
                            + " (" + formatBytes(run.getFileSizeBytes()) + "). Elle apparaît dans l'historique "
                            + "et peut être restaurée comme n'importe quelle autre sauvegarde.");
        } catch (BackupException e) {
            flash.addFlashAttribute("flashError", "Import échoué : " + e.getMessage());
        }
        return "redirect:/admin/backup";
    }

    // ── Purge manuelle explicite de l'historique ─────────────────────────

    @PostMapping("/purge")
    public String purge(@RequestParam("beforeDate") LocalDate beforeDate, RedirectAttributes flash) {
        OffsetDateTime cutoff = beforeDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        var result = executionService.purgeHistoryBefore(cutoff);
        flash.addFlashAttribute("flashSuccess",
                "Historique purgé avant le " + beforeDate + " : " + result.runsDeleted()
                        + " sauvegarde(s) et " + result.restoresDeleted() + " restauration(s) supprimées.");
        return "redirect:/admin/backup";
    }

    // ── Suppression fine d'une seule sauvegarde (audit 01/08/2026) ───────
    // Toujours via une page de confirmation dediee (jamais un simple clic,
    // meme logique que la restauration) : elle liste precisement les
    // restaurations encore liees, s'il y en a, pour permettre une
    // suppression en cascade explicite plutot qu'un refus sec (demande
    // utilisateur du 01/08/2026, suite au bouton "Supprimer" simple livre
    // plus tot le meme jour).

    @GetMapping("/runs/{id}/delete-confirm")
    public String deleteConfirm(@PathVariable Long id, Model model) {
        BackupRun run = backupRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BackupRun", id));
        var linkedRestores = executionService.findLinkedRestores(id).stream()
                .map(this::toRestoreView)
                .toList();
        model.addAttribute("run", toRunView(run));
        model.addAttribute("linkedRestores", linkedRestores);
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Confirmer la suppression");
        return "admin/backup-delete-confirm";
    }

    @PostMapping("/runs/{id}/delete")
    public String deleteRun(@PathVariable Long id,
                            @RequestParam(defaultValue = "false") boolean cascade,
                            RedirectAttributes flash) {
        try {
            if (cascade) {
                executionService.deleteRunCascade(id);
                flash.addFlashAttribute("flashSuccess",
                        "Sauvegarde supprimée de l'historique, avec la ou les restauration(s) liée(s).");
            } else {
                executionService.deleteRun(id);
                flash.addFlashAttribute("flashSuccess", "Sauvegarde supprimée de l'historique.");
            }
        } catch (BackupException e) {
            flash.addFlashAttribute("flashError", "Suppression impossible : " + e.getMessage());
        }
        return "redirect:/admin/backup";
    }

    // ── Téléchargement ───────────────────────────────────────────────────

    @GetMapping("/runs/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, RedirectAttributes flash) {
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

    // ── Restauration — jamais un simple clic ────────────────────────────

    @GetMapping("/runs/{id}/restore-confirm")
    public String restoreConfirm(@PathVariable Long id, Model model) {
        return restoreConfirmPage(id, model);
    }

    private String restoreConfirmPage(Long id, Model model) {
        BackupRun run = backupRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BackupRun", id));
        model.addAttribute("run", toRunView(run));
        model.addAttribute("fileAvailable", executionService.isFileAvailable(run));
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Confirmer la restauration");
        return "admin/backup-restore-confirm";
    }

    @PostMapping("/runs/{id}/restore")
    public String restore(@PathVariable Long id,
                          @RequestParam String confirmationText,
                          Authentication auth,
                          Model model,
                          RedirectAttributes flash) {
        try {
            executionService.restore(id, confirmationText, auth.getName());
            flash.addFlashAttribute("flashSuccess",
                    "Restauration terminée avec succès. Vérifiez le bon fonctionnement de l'application.");
            return "redirect:/admin/backup";
        } catch (BackupException e) {
            if (e.getReason() == BackupException.Reason.CONFIRMATION_MISMATCH) {
                model.addAttribute("confirmError", "Le texte saisi ne correspond pas au nom du fichier.");
                return restoreConfirmPage(id, model);
            }
            flash.addFlashAttribute("flashError", "Restauration échouée : " + e.getMessage());
            return "redirect:/admin/backup";
        }
    }

    // ── Modèle du tableau de bord ────────────────────────────────────────

    private void prepareDashboardModel(Model model, int page, BackupSettingsForm form) {
        var settings = configurationService.effectiveSettings();
        var history = backupRunRepository.findAllByOrderByStartedAtDesc(
                        PageRequest.of(page, HISTORY_PAGE_SIZE, Sort.by("startedAt").descending()))
                .map(this::toRunView);
        var restoreHistory = backupRestoreRepository.findAllByOrderByStartedAtDesc(
                        PageRequest.of(0, 10))
                .map(this::toRestoreView);

        var lastRun = backupRunRepository.findFirstByStatusOrderByStartedAtDesc(BackupRun.STATUS_SUCCESS)
                .map(this::toRunView).orElse(null);
        var lastFailedRun = backupRunRepository.findFirstByStatusOrderByStartedAtDesc(BackupRun.STATUS_FAILED)
                .map(this::toRunView).orElse(null);
        OffsetDateTime nextRunAt = configurationService.nextRunAt(OffsetDateTime.now());

        model.addAttribute("backupForm", form);
        model.addAttribute("enabled", settings.enabled());
        model.addAttribute("cronExpression", settings.cronExpression());
        model.addAttribute("retentionCount", settings.retentionCount());
        model.addAttribute("nextRunAt", nextRunAt);
        model.addAttribute("lastRun", lastRun);
        model.addAttribute("lastFailedRun", lastFailedRun);
        model.addAttribute("totalBackupCount", backupRunRepository.countByStatus(BackupRun.STATUS_SUCCESS));
        model.addAttribute("totalStorageBytes", executionService.totalStorageBytes());
        model.addAttribute("totalStorageFormatted", formatBytes(executionService.totalStorageBytes()));
        model.addAttribute("storagePath", executionService.storagePath());
        model.addAttribute("encryptionEnabled", executionService.isEncryptionEnabled());
        model.addAttribute("operationInProgress", executionService.isOperationInProgress());
        model.addAttribute("history", history);
        model.addAttribute("restoreHistory", restoreHistory);
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", "Sauvegardes");
    }

    private BackupRunView toRunView(BackupRun run) {
        return new BackupRunView(
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
                executionService.isFileAvailable(run),
                run.isEncrypted());
    }

    private BackupRestoreView toRestoreView(BackupRestore restore) {
        String backupFileName = backupRunRepository.findById(restore.getBackupRunId())
                .map(BackupRun::getFileName).orElse(null);
        return new BackupRestoreView(
                restore.getId(),
                backupFileName,
                restore.getStatus(),
                restore.getStartedAt(),
                restore.getFinishedAt(),
                restore.getErrorMessage(),
                restore.getPerformedBy());
    }

    private static String formatBytes(Long bytes) {
        if (bytes == null) return "taille inconnue";
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return String.format("%.1f Ko", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f Mo", bytes / (1024.0 * 1024));
        return String.format("%.2f Go", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatDuration(Long seconds) {
        if (seconds == null) return "—";
        if (seconds < 60) return seconds + " s";
        long min = seconds / 60;
        long rem = seconds % 60;
        return min + " min " + rem + " s";
    }

    /** Vue Thymeleaf d'une exécution de sauvegarde (évite d'exposer l'entité JPA à la vue). */
    public record BackupRunView(
            Long id,
            String triggerSource,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String fileName,
            Long fileSizeBytes,
            String checksumSha256,
            String errorMessage,
            String triggeredBy,
            String label,
            boolean fileAvailable,
            /** {@code true} si le fichier est chiffré (AES-256-GCM + HMAC-SHA256, audit 01/08/2026). */
            boolean encrypted
    ) {
        public Long durationSeconds() {
            if (startedAt == null || finishedAt == null) return null;
            return java.time.Duration.between(startedAt, finishedAt).getSeconds();
        }

        public String durationFormatted() {
            return formatDuration(durationSeconds());
        }

        public String fileSizeFormatted() {
            return formatBytes(fileSizeBytes);
        }

        public String checksumShort() {
            if (checksumSha256 == null || checksumSha256.length() <= 12) return checksumSha256;
            return checksumSha256.substring(0, 12) + "…";
        }
    }

    /** Vue Thymeleaf d'une opération de restauration. */
    public record BackupRestoreView(
            Long id,
            String backupFileName,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String errorMessage,
            String performedBy
    ) {}
}
