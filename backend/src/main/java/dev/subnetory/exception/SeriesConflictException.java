package dev.subnetory.exception;

import dev.subnetory.dto.SeriesReservationResponse;

/**
 * La serie demandee ne peut pas etre creee telle quelle (adresse deja utilisee,
 * passerelle, hors plage, plage epuisee). Aucune adresse n'a ete creee ; l'apercu
 * detaille permet d'afficher les lignes en conflit.
 */
public class SeriesConflictException extends ConflictException {

    private final transient SeriesReservationResponse preview;

    public SeriesConflictException(String message, SeriesReservationResponse preview) {
        super(message);
        this.preview = preview;
    }

    public SeriesReservationResponse getPreview() {
        return preview;
    }
}
