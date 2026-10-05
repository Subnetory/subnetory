package dev.subnetory.web.form;

/**
 * Une ligne du tableau « plusieurs séries enchaînées » de la page de réservation
 * multiple (ex. HOST, CVM, IPMI). Une ligne sans nombre d'adresses est ignorée.
 *
 * <p>Les champs ne portent aucune contrainte Bean Validation : les valeurs sont
 * contrôlées par {@code SeriesReservationService} (messages d'erreur communs à l'API).</p>
 */
public class SeriesGroupForm {

    private String label = "";
    private Integer count;
    private String prefix = "";
    private Long startCounter = 1L;
    private Integer counterWidth = 2;
    private String suffix = "";

    public String getLabel()               { return label; }
    public void setLabel(String label)     { this.label = label; }

    public Integer getCount()              { return count; }
    public void setCount(Integer count)    { this.count = count; }

    public String getPrefix()              { return prefix; }
    public void setPrefix(String prefix)   { this.prefix = prefix; }

    public Long getStartCounter()                      { return startCounter; }
    public void setStartCounter(Long startCounter)     { this.startCounter = startCounter; }

    public Integer getCounterWidth()                       { return counterWidth; }
    public void setCounterWidth(Integer counterWidth)      { this.counterWidth = counterWidth; }

    public String getSuffix()              { return suffix; }
    public void setSuffix(String suffix)   { this.suffix = suffix; }
}
