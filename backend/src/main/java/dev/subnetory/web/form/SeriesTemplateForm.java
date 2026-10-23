package dev.subnetory.web.form;

import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.dto.SeriesTemplateRequest;
import dev.subnetory.dto.SeriesTemplateResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * Formulaire de création / modification d'un template personnalisé de réservation en série
 * (page /network/series-templates). Les groupes sont saisis dans un tableau de
 * {@value #GROUP_LINES} lignes ; les lignes vides sont ignorées.
 */
public class SeriesTemplateForm {

    public static final int GROUP_LINES = 10;

    private Long id;

    @NotNull(message = "{validation.field.contextRequired}")
    private Long contextId;

    @NotBlank(message = "{validation.field.nameRequired}")
    @Size(max = 100, message = "{validation.size.max}")
    private String name;

    @Size(max = 500, message = "{validation.size.max}")
    private String description;

    private String allocation = SeriesTemplateDefinition.SEQUENTIAL;
    private Integer groupGap = 0;
    private Integer ipStep = 1;
    private String onOccupied = "block";

    private List<SeriesTemplateGroupForm> groups = newGroupLines();

    public SeriesTemplateForm() {}

    /** Pré-remplit le formulaire depuis un template existant (preset ou personnalisé). */
    public static SeriesTemplateForm from(SeriesTemplateResponse template) {
        SeriesTemplateForm f = new SeriesTemplateForm();
        f.id = template.id();
        f.contextId = template.contextId();
        f.name = template.name();
        f.description = template.description();
        SeriesTemplateDefinition definition = template.definition();
        f.allocation = definition.allocation();
        f.groupGap = definition.groupGap();
        f.ipStep = definition.ipStep();
        f.onOccupied = definition.onOccupied();
        List<SeriesTemplateGroupForm> lines = new ArrayList<>();
        for (SeriesTemplateDefinition.Group g : definition.groups()) {
            SeriesTemplateGroupForm line = new SeriesTemplateGroupForm();
            line.setRole(g.role());
            line.setLabel(g.label() == null ? "" : g.label());
            line.setFormat(g.format());
            line.setCount(g.count());
            line.setStartCounter(g.startCounter());
            lines.add(line);
        }
        while (lines.size() < GROUP_LINES) {
            lines.add(new SeriesTemplateGroupForm());
        }
        f.groups = lines;
        return f;
    }

    /** Construit la requête du service ; les lignes de groupe vides sont ignorées. */
    public SeriesTemplateRequest toRequest() {
        List<SeriesTemplateDefinition.Group> definitionGroups = groups.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(g -> new SeriesTemplateDefinition.Group(
                        g.getRole() == null ? null : g.getRole().trim(),
                        g.getLabel() == null || g.getLabel().isBlank() ? null : g.getLabel().trim(),
                        g.getFormat() == null ? null : g.getFormat().trim(),
                        g.getCount(),
                        g.getStartCounter()))
                .toList();
        return new SeriesTemplateRequest(contextId, name, description,
                new SeriesTemplateDefinition(allocation, groupGap, ipStep, onOccupied, definitionGroups));
    }

    private static List<SeriesTemplateGroupForm> newGroupLines() {
        List<SeriesTemplateGroupForm> lines = new ArrayList<>();
        for (int i = 0; i < GROUP_LINES; i++) {
            lines.add(new SeriesTemplateGroupForm());
        }
        return lines;
    }

    public Long getId()                    { return id; }
    public void setId(Long id)             { this.id = id; }

    public Long getContextId()                 { return contextId; }
    public void setContextId(Long contextId)   { this.contextId = contextId; }

    public String getName()                { return name; }
    public void setName(String name)       { this.name = name; }

    public String getDescription()                     { return description; }
    public void setDescription(String description)     { this.description = description; }

    public String getAllocation()                  { return allocation; }
    public void setAllocation(String allocation)   { this.allocation = allocation; }

    public Integer getGroupGap()                   { return groupGap; }
    public void setGroupGap(Integer groupGap)      { this.groupGap = groupGap; }

    public Integer getIpStep()                 { return ipStep; }
    public void setIpStep(Integer ipStep)      { this.ipStep = ipStep; }

    public String getOnOccupied()                  { return onOccupied; }
    public void setOnOccupied(String onOccupied)   { this.onOccupied = onOccupied; }

    public List<SeriesTemplateGroupForm> getGroups()                   { return groups; }
    public void setGroups(List<SeriesTemplateGroupForm> groups)        { this.groups = groups; }
}
