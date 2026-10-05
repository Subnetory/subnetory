package dev.subnetory.dto;

import java.util.List;

/**
 * Resultat d'un apercu ou d'une creation de serie.
 *
 * <p>Statuts d'une entree : {@code AVAILABLE}, {@code OCCUPIED}, {@code RESERVED}
 * (passerelle), {@code OUT_OF_RANGE}.</p>
 *
 * @param network   sous-reseau cible (CIDR)
 * @param mode      {@code block} ou {@code skip}
 * @param canCreate vrai si la serie peut etre creee telle quelle
 * @param requested nombre d'adresses demandees
 * @param shortfall adresses manquantes si la plage est epuisee (mode skip)
 * @param rows      adresses de la serie
 * @param skipped   adresses sautees (mode skip)
 */
public record SeriesReservationResponse(
        String network,
        String mode,
        boolean canCreate,
        int requested,
        int shortfall,
        List<Entry> rows,
        List<Entry> skipped
) {
    public record Entry(String ip, String hostname, String status) {}
}
