package dev.subnetory.web.form;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Une ligne du tableau de réservation multiple d'adresses IP.
 *
 * <p>L'adresse est pré-remplie côté serveur (suggestion IP libre) et affichée en
 * lecture seule dans le formulaire — elle n'est jamais ressaisie par l'utilisateur,
 * ce qui évite d'avoir à revalider un format IPv4/CIDR côté ligne.</p>
 */
public class BulkReservationRow {

    private String address;

    @Size(max = 100, message = "100 caractères maximum")
    private String hostname;

    @Size(max = 500, message = "500 caractères maximum")
    private String description;

    @Pattern(
        regexp = "^$|^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$",
        message = "Format MAC invalide (ex : aa:bb:cc:dd:ee:ff)"
    )
    private String mac;

    private boolean temporary;

    /** Décoché = ligne ignorée à la soumission finale, sans être supprimée du tableau. */
    private boolean included = true;

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
}
