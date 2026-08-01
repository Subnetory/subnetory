package dev.subnetory.dto;

import java.time.OffsetDateTime;

/** Phase 7 audit, 31/07/2026 — une ligne d'historique de sauvegarde. */
public record BackupRunResponse(
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
        /** Titre/commentaire optionnel saisi par l'administrateur (audit 01/08/2026). */
        String label,
        /** {@code true} si le fichier est chiffré (AES-256-GCM + HMAC-SHA256, audit 01/08/2026). */
        boolean encrypted
) {
    public Long durationSeconds() {
        if (startedAt == null || finishedAt == null) return null;
        return java.time.Duration.between(startedAt, finishedAt).getSeconds();
    }
}
