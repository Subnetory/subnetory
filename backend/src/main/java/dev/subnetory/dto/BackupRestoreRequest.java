package dev.subnetory.dto;

/**
 * Phase 7 audit, 31/07/2026 — declenchement d'une restauration.
 *
 * <p>{@code confirmationText} doit correspondre exactement au nom de
 * fichier de la sauvegarde source (voir {@code RESTORE_OPERATIONS.md} :
 * une restauration ne doit jamais etre un simple clic). Verifie par
 * {@code BackupExecutionService.restore(...)}.</p>
 */
public record BackupRestoreRequest(
        Long backupRunId,
        String confirmationText
) {}
