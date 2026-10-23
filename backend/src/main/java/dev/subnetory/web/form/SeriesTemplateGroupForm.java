package dev.subnetory.web.form;

/**
 * Une ligne du tableau des groupes d'un template (ex. HOST, CVM, IPMI). Une ligne dont le
 * rôle et le format sont vides est ignorée. Aucune contrainte Bean Validation : les valeurs
 * sont contrôlées par {@code SeriesTemplateExpander#validate} (messages communs à l'API).
 */
public class SeriesTemplateGroupForm {

    private String role = "";
    private String label = "";
    private String format = "";
    private Integer count;
    private Long startCounter = 1L;

    public String getRole()                { return role; }
    public void setRole(String role)       { this.role = role; }

    public String getLabel()               { return label; }
    public void setLabel(String label)     { this.label = label; }

    public String getFormat()              { return format; }
    public void setFormat(String format)   { this.format = format; }

    public Integer getCount()              { return count; }
    public void setCount(Integer count)    { this.count = count; }

    public Long getStartCounter()                      { return startCounter; }
    public void setStartCounter(Long startCounter)     { this.startCounter = startCounter; }

    /** Ligne sans rôle ni format : ignorée à l'enregistrement. */
    public boolean isBlank() {
        return (role == null || role.isBlank()) && (format == null || format.isBlank());
    }
}
