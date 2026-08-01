package dev.subnetory.dto;

/** Phase 7 audit, 31/07/2026 — payload de mise a jour de la configuration. */
public record BackupSettingsRequest(
        boolean enabled,
        String cronExpression,
        int retentionCount
) {}
