package dev.subnetory.dto;

import java.time.LocalDate;

/**
 * Audit du 01/08/2026 — corps de {@code POST /api/v1/admin/backup/purge}.
 *
 * <p>Supprime définitivement les sauvegardes et restaurations strictement
 * antérieures à {@code beforeDate} (voir
 * {@code BackupExecutionService#purgeHistoryBefore}).</p>
 */
public record BackupPurgeRequest(LocalDate beforeDate) {}
