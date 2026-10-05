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
 *
 * <p>Mode série : les champs {@code series*} décrivent une série d'adresses contiguës
 * à noms incrémentés ; leur génération remplit {@code rows} et passe {@code atomic} à
 * vrai. Les lignes sont alors créées <b>toutes ensemble ou pas du tout</b> par
 * {@code SeriesReservationService}, et l'adresse de chaque ligne reste modifiable.</p>
 */
public class BulkReservationForm {

    @NotNull(message = "{validation.field.subnetRequired}")
    private Long subnetId;

    @Min(value = 1, message = "{validation.reserve.additionalCount.min}")
    @Max(value = 50, message = "{validation.reserve.additionalCount.max}")
    private int additionalCount = 10;

    private List<@Valid BulkReservationRow> rows = new ArrayList<>();

    // ── Mode série ─────────────────────────────────────────────────────────

    private String seriesStartIp;
    private Integer seriesCount = 3;
    private Integer seriesStep = 1;
    private String seriesPrefix = "";
    private Long seriesStartCounter = 1L;
    private Integer seriesCounterWidth = 2;
    private String seriesSuffix = "";
    private String seriesDescription;
    private boolean seriesSkipOccupied;

    /** Vrai quand les lignes viennent d'une série : création atomique, adresse modifiable. */
    private boolean atomic;

    public Long getSubnetId()              { return subnetId; }
    public void setSubnetId(Long subnetId) { this.subnetId = subnetId; }

    public int getAdditionalCount()                    { return additionalCount; }
    public void setAdditionalCount(int additionalCount) { this.additionalCount = additionalCount; }

    public List<BulkReservationRow> getRows()               { return rows; }
    public void setRows(List<BulkReservationRow> rows)      { this.rows = rows; }

    public String getSeriesStartIp()                   { return seriesStartIp; }
    public void setSeriesStartIp(String seriesStartIp) { this.seriesStartIp = seriesStartIp; }

    public Integer getSeriesCount()                    { return seriesCount; }
    public void setSeriesCount(Integer seriesCount)    { this.seriesCount = seriesCount; }

    public Integer getSeriesStep()                     { return seriesStep; }
    public void setSeriesStep(Integer seriesStep)      { this.seriesStep = seriesStep; }

    public String getSeriesPrefix()                    { return seriesPrefix; }
    public void setSeriesPrefix(String seriesPrefix)   { this.seriesPrefix = seriesPrefix; }

    public Long getSeriesStartCounter()                        { return seriesStartCounter; }
    public void setSeriesStartCounter(Long seriesStartCounter) { this.seriesStartCounter = seriesStartCounter; }

    public Integer getSeriesCounterWidth()                         { return seriesCounterWidth; }
    public void setSeriesCounterWidth(Integer seriesCounterWidth)  { this.seriesCounterWidth = seriesCounterWidth; }

    public String getSeriesSuffix()                    { return seriesSuffix; }
    public void setSeriesSuffix(String seriesSuffix)   { this.seriesSuffix = seriesSuffix; }

    public String getSeriesDescription()                       { return seriesDescription; }
    public void setSeriesDescription(String seriesDescription) { this.seriesDescription = seriesDescription; }

    public boolean isSeriesSkipOccupied()                          { return seriesSkipOccupied; }
    public void setSeriesSkipOccupied(boolean seriesSkipOccupied)  { this.seriesSkipOccupied = seriesSkipOccupied; }

    public boolean isAtomic()              { return atomic; }
    public void setAtomic(boolean atomic)  { this.atomic = atomic; }
}
