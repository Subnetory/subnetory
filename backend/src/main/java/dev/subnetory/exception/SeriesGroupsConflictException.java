package dev.subnetory.exception;

import dev.subnetory.dto.SeriesGroupsResponse;

/**
 * Les series enchainees demandees ne peuvent pas etre creees telles quelles
 * (adresse deja utilisee, passerelle, hors plage, plage epuisee). Aucune adresse
 * n'a ete creee ; l'apercu detaille, groupe par groupe, permet d'afficher les
 * lignes en conflit.
 */
public class SeriesGroupsConflictException extends ConflictException {

    private final transient SeriesGroupsResponse preview;

    public SeriesGroupsConflictException(String message, SeriesGroupsResponse preview) {
        super(message);
        this.preview = preview;
    }

    public SeriesGroupsResponse getPreview() {
        return preview;
    }
}
