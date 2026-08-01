package dev.subnetory.dto;

import java.util.List;

/**
 * Réponse de POST /api/v1/addresses/import/csv.
 */
public record CsvImportResponse(
        int totalRows,
        int created,
        int updatedLastSeen,
        int skipped,
        int errors,
        List<CsvRowError> errorDetails
) {
    /**
     * Détail d'une erreur sur une ligne CSV.
     *
     * @param row     numéro de ligne dans le fichier (1 = première ligne de données, hors header)
     * @param address valeur de la colonne address pour cette ligne (peut être null si absent)
     * @param reason  raison lisible de l'erreur, actionnable par l'admin
     */
    public record CsvRowError(int row, String address, String reason) {}
}
