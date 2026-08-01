package dev.subnetory.dto;

/** Audit du 01/08/2026 — résultat d'une purge manuelle de l'historique. */
public record BackupPurgeResponse(int runsDeleted, int restoresDeleted) {}
