package dev.subnetory.dto;

import java.time.OffsetDateTime;

/**
 * Etat courant de la configuration de sauvegarde, enrichi d'indicateurs
 * calcules (prochaine execution, derniere sauvegarde) pour le tableau de
 * bord admin. Phase 7 audit, 31/07/2026.
 */
public record BackupSettingsResponse(
        boolean enabled,
        String cronExpression,
        int retentionCount,
        String storagePath,
        OffsetDateTime nextRunAt,
        BackupRunResponse lastRun,
        long totalBackupCount,
        long totalStorageBytes,
        /**
         * {@code true} si une clé de chiffrement des sauvegardes est
         * configurée (audit 01/08/2026) — jamais la clé elle-même, qui n'est
         * ni stockée en base ni exposée par l'API (voir
         * {@code subnetory.backup.encryption.key}).
         */
        boolean encryptionEnabled
) {}
