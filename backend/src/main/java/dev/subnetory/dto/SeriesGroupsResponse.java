package dev.subnetory.dto;

import java.util.List;

/**
 * Resultat d'un apercu ou d'une creation de plusieurs series enchainees.
 *
 * @param network   sous-reseau de la demande (CIDR) ; chaque groupe indique le sien
 * @param mode      {@code block} ou {@code skip}
 * @param canCreate vrai si tous les groupes peuvent etre crees tels quels
 * @param requested nombre total d'adresses demandees
 * @param groups    resultat de chaque groupe, dans l'ordre de la demande
 */
public record SeriesGroupsResponse(
        String network,
        String mode,
        boolean canCreate,
        int requested,
        List<Group> groups
) {

    /**
     * Resultat d'un groupe ; statuts des entrees : {@code AVAILABLE}, {@code OCCUPIED},
     * {@code RESERVED} (passerelle), {@code OUT_OF_RANGE}.
     *
     * @param label     libelle du groupe (peut etre vide)
     * @param requested adresses demandees pour ce groupe
     * @param shortfall adresses manquantes si la plage est epuisee (mode skip)
     * @param rows      adresses du groupe
     * @param skipped   adresses sautees (mode skip)
     * @param subnetId  sous-reseau du groupe (null dans une reponse construite sans)
     * @param network   sous-reseau du groupe (CIDR)
     */
    public record Group(
            String label,
            int requested,
            int shortfall,
            List<SeriesReservationResponse.Entry> rows,
            List<SeriesReservationResponse.Entry> skipped,
            Long subnetId,
            String network
    ) {
        /** Groupe sans information de sous-reseau. */
        public Group(String label, int requested, int shortfall,
                     List<SeriesReservationResponse.Entry> rows,
                     List<SeriesReservationResponse.Entry> skipped) {
            this(label, requested, shortfall, rows, skipped, null, null);
        }
    }
}
