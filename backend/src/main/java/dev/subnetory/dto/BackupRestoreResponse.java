package dev.subnetory.dto;

import java.time.OffsetDateTime;

/** Phase 7 audit, 31/07/2026 — une ligne du journal de restauration. */
public record BackupRestoreResponse(
        Long id,
        Long backupRunId,
        String backupFileName,
        Long safetyBackupRunId,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String errorMessage,
        String performedBy
) {}
