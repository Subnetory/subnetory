package dev.subnetory.dto;

/**
 * Audit du 01/08/2026 — corps optionnel de {@code POST /api/v1/admin/backup/trigger}.
 *
 * <p>{@code label} est facultatif : {@code null} ou une chaîne vide équivaut
 * à ne pas fournir de titre/commentaire.</p>
 */
public record BackupTriggerRequest(String label) {}
