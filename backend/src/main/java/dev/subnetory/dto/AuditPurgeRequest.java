package dev.subnetory.dto;

import java.time.LocalDate;

/**
 * Audit du 01/08/2026 (backlog #27) — corps de
 * {@code POST /api/v1/admin/audit-log/purge}.
 *
 * <p>Supprime définitivement les entrées du journal d'audit strictement
 * antérieures à {@code beforeDate}. Complète la purge automatique
 * planifiée ({@code AuthAuditRetentionScheduler}, {@code
 * subnetory.audit.retention.days}, 90 jours par défaut) par une action
 * manuelle immédiate — même logique que {@link BackupPurgeRequest} pour les
 * sauvegardes.</p>
 */
public record AuditPurgeRequest(LocalDate beforeDate) {}
