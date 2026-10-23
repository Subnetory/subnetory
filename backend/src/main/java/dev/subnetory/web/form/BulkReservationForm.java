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

    // ── Mode plusieurs séries enchaînées ───────────────────────────────────

    /** Nombre de lignes de groupes proposées dans le formulaire. */
    public static final int GROUP_LINES = 5;

    private Integer seriesGap = 0;
    private List<SeriesGroupForm> groups = newGroupLines();

    /** Vrai quand les lignes viennent de plusieurs séries : affiche la colonne « groupe ». */
    private boolean grouped;

    // ── Mode template (préparation cluster / infrastructure) ──────────────

    /** Clé du template choisi : {@code builtin:<clé>} ou {@code custom:<id>}. */
    private String templateKey;
    private String templateCluster;
    private String templateSite;
    private Integer templateNodes = 3;
    private String templateStartIp;
    /** Écart, pas et comportement sur adresse occupée : vides, ceux du template s'appliquent. */
    private Integer templateGap;
    private Integer templateStep;
    private String templateOccupied;
    /** Sous-réseau et IP de départ par rôle (templates « un sous-réseau par rôle »). */
    private List<@Valid TemplateRoleForm> templateRoles = newRoleLines();

    /** Nombre maximal de rôles d'un template. */
    public static final int ROLE_LINES = 10;

    /** Vrai quand les lignes viennent de plusieurs sous-réseaux : affiche la colonne « sous-réseau ». */
    private boolean multiSubnet;

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

    public Integer getSeriesGap()                  { return seriesGap; }
    public void setSeriesGap(Integer seriesGap)    { this.seriesGap = seriesGap; }

    public List<SeriesGroupForm> getGroups()                   { return groups; }
    public void setGroups(List<SeriesGroupForm> groups)        { this.groups = groups; }

    public String getTemplateKey()                     { return templateKey; }
    public void setTemplateKey(String templateKey)     { this.templateKey = templateKey; }

    public String getTemplateCluster()                         { return templateCluster; }
    public void setTemplateCluster(String templateCluster)     { this.templateCluster = templateCluster; }

    public String getTemplateSite()                    { return templateSite; }
    public void setTemplateSite(String templateSite)   { this.templateSite = templateSite; }

    public Integer getTemplateNodes()                      { return templateNodes; }
    public void setTemplateNodes(Integer templateNodes)    { this.templateNodes = templateNodes; }

    public String getTemplateStartIp()                         { return templateStartIp; }
    public void setTemplateStartIp(String templateStartIp)     { this.templateStartIp = templateStartIp; }

    public Integer getTemplateGap()                    { return templateGap; }
    public void setTemplateGap(Integer templateGap)    { this.templateGap = templateGap; }

    public Integer getTemplateStep()                   { return templateStep; }
    public void setTemplateStep(Integer templateStep)  { this.templateStep = templateStep; }

    public String getTemplateOccupied()                        { return templateOccupied; }
    public void setTemplateOccupied(String templateOccupied)   { this.templateOccupied = templateOccupied; }

    public List<TemplateRoleForm> getTemplateRoles()                       { return templateRoles; }
    public void setTemplateRoles(List<TemplateRoleForm> templateRoles)     { this.templateRoles = templateRoles; }

    public boolean isMultiSubnet()                     { return multiSubnet; }
    public void setMultiSubnet(boolean multiSubnet)    { this.multiSubnet = multiSubnet; }

    public boolean isGrouped()                 { return grouped; }
    public void setGrouped(boolean grouped)    { this.grouped = grouped; }

    private static List<TemplateRoleForm> newRoleLines() {
        List<TemplateRoleForm> lines = new ArrayList<>();
        for (int i = 0; i < ROLE_LINES; i++) {
            lines.add(new TemplateRoleForm());
        }
        return lines;
    }

    private static List<SeriesGroupForm> newGroupLines() {
        List<SeriesGroupForm> lines = new ArrayList<>();
        for (int i = 0; i < GROUP_LINES; i++) {
            lines.add(new SeriesGroupForm());
        }
        return lines;
    }

    public boolean isAtomic()              { return atomic; }
    public void setAtomic(boolean atomic)  { this.atomic = atomic; }
}
