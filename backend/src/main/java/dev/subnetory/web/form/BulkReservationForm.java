package dev.subnetory.web.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Formulaire de réservation multiple d'adresses IP (page /network/addresses/reserve).
 *
 * <p>Cycle de vie en trois soumissions successives sur la même page :</p>
 * <ol>
 *   <li>{@code additionalCount} pilote la génération initiale ou l'ajout de lignes
 *       supplémentaires — les IP libres sont suggérées par {@code IpAllocService} et
 *       ajoutées à {@code rows} sans dupliquer les adresses déjà présentes.</li>
 *   <li>L'utilisateur édite hostname/description/MAC/temporaire par ligne, et peut
 *       décocher {@code included} pour ignorer une ligne sans la supprimer.</li>
 *   <li>La soumission finale ne transmet que les lignes {@code included=true} à
 *       {@code AddressService.bulkUpsert()}.</li>
 * </ol>
 */
public class BulkReservationForm {

    @NotNull(message = "{validation.field.subnetRequired}")
    private Long subnetId;

    @Min(value = 1, message = "{validation.reserve.additionalCount.min}")
    @Max(value = 50, message = "{validation.reserve.additionalCount.max}")
    private int additionalCount = 10;

    @Valid
    private List<BulkReservationRow> rows = new ArrayList<>();

    public Long getSubnetId()              { return subnetId; }
    public void setSubnetId(Long subnetId) { this.subnetId = subnetId; }

    public int getAdditionalCount()                    { return additionalCount; }
    public void setAdditionalCount(int additionalCount) { this.additionalCount = additionalCount; }

    public List<BulkReservationRow> getRows()               { return rows; }
    public void setRows(List<BulkReservationRow> rows)      { this.rows = rows; }
}
