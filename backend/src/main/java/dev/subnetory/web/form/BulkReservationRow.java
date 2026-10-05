package dev.subnetory.web.form;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Une ligne du tableau de réservation multiple d'adresses IP.
 *
 * <p>L'adresse est pré-remplie côté serveur (suggestion IP libre) et affichée en
 * lecture seule dans le formulaire. En mode série ({@link BulkReservationForm#isAtomic()})
 * elle devient modifiable ligne par ligne : le format est alors contrôlé par
 * {@code SeriesReservationService}.</p>
 */
public class BulkReservationRow {

    private String address;

    @Size(max = 100, message = "{validation.size.max}")
    private String hostname;

    @Size(max = 500, message = "{validation.size.max}")
    private String description;

    @Pattern(
        regexp = "^$|^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$",
        message = "{validation.mac.format}"
    )
    private String mac;

    private boolean temporary;

    /** Décoché = ligne ignorée à la soumission finale, sans être supprimée du tableau. */
    private boolean included = true;

    /**
     * Résultat du dernier contrôle de la ligne en mode série (AVAILABLE, OCCUPIED,
     * RESERVED, OUT_OF_RANGE, INVALID, DUPLICATE) ; {@code null} tant que la ligne n'a
     * pas été contrôlée. Affichage seul : jamais relu comme une décision.
     */
    private String status;

    public BulkReservationRow() {}

    public BulkReservationRow(String address) {
        this.address = address;
    }

    public String getAddress()                { return address; }
    public void setAddress(String address)     { this.address = address; }

    public String getHostname()                { return hostname; }
    public void setHostname(String hostname)   { this.hostname = hostname; }

    public String getDescription()                 { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getMac()             { return mac; }
    public void setMac(String mac)     { this.mac = mac; }

    public boolean isTemporary()                { return temporary; }
    public void setTemporary(boolean temporary) { this.temporary = temporary; }

    public boolean isIncluded()                { return included; }
    public void setIncluded(boolean included)  { this.included = included; }

    public String getStatus()              { return status; }
    public void setStatus(String status)   { this.status = status; }
}
