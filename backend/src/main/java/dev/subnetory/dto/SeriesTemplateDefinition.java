package dev.subnetory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Definition d'un template de reservation en serie : le document JSON stocke (template
 * personnalise) ou livre (preset), sans aucun code specifique a un constructeur.
 *
 * <p>Parametres de comportement (valeurs par defaut, modifiables a chaque utilisation) :</p>
 * <ul>
 *   <li>{@code allocation} : {@code groups-sequential} (defaut : tous les hotes, puis toutes
 *       les CVM, puis tous les IPMI) ou {@code interleaved} (noeud 1 = hote + CVM + IPMI,
 *       puis noeud 2...) ou {@code per-group} (un bloc d'adresses par role, chacun dans son
 *       propre sous-reseau et a sa propre IP de depart, choisis a l'utilisation : par exemple
 *       IPMI en gestion, hotes en serveurs, CVM dans un troisieme sous-reseau) ;</li>
 *   <li>{@code groupGap} : adresses libres entre deux groupes (0 par defaut, mode
 *       sequentiel) ;</li>
 *   <li>{@code ipStep} : pas entre deux adresses (1 par defaut) ;</li>
 *   <li>{@code onOccupied} : {@code block} (defaut) ou {@code skip}.</li>
 * </ul>
 *
 * <p>Le controle semantique (formats de nom, variables) est fait par
 * {@code SeriesTemplateExpander#validate}.</p>
 */
public record SeriesTemplateDefinition(
        @Pattern(regexp = "^(groups-sequential|interleaved|per-group)$",
                message = "allocation must be one of: groups-sequential, interleaved, per-group")
        String allocation,

        @Min(value = 0, message = "groupGap must be at least 0")
        @Max(value = 256, message = "groupGap must be at most 256")
        Integer groupGap,

        @Min(value = 1, message = "ipStep must be at least 1")
        @Max(value = 256, message = "ipStep must be at most 256")
        Integer ipStep,

        @Pattern(regexp = "^(block|skip)$", message = "onOccupied must be one of: block, skip")
        String onOccupied,

        @NotEmpty(message = "At least one group is required")
        @Size(max = 10, message = "At most 10 groups are allowed")
        List<@Valid @NotNull(message = "Group must not be null") Group> groups
) {

    public static final String SEQUENTIAL = "groups-sequential";
    public static final String INTERLEAVED = "interleaved";
    public static final String PER_GROUP = "per-group";

    public SeriesTemplateDefinition {
        if (allocation == null) allocation = SEQUENTIAL;
        if (groupGap == null) groupGap = 0;
        if (ipStep == null) ipStep = 1;
        if (onOccupied == null) onOccupied = "block";
    }

    /**
     * Un groupe d'adresses du template.
     *
     * @param role         role du groupe (HOST, CVM, ILO...), valeur de la variable {@code {role}}
     * @param label        libelle affiche dans l'apercu (le role par defaut)
     * @param format       modele de nom : variables {@code {cluster}}, {@code {site}},
     *                     {@code {role}} et exactement un compteur {@code {n}}, {@code {nn}},
     *                     {@code {nnn}}... (le nombre de {@code n} donne le nombre de chiffres)
     * @param count        nombre d'adresses fixe ; absent, le nombre de noeuds de la demande
     * @param startCounter premiere valeur du compteur (1 par defaut)
     */
    public record Group(
            @NotBlank(message = "Role is required")
            @Pattern(regexp = "^[A-Za-z0-9_-]{1,30}$",
                    message = "Role must be 1 to 30 letters, digits, '-' or '_'")
            String role,

            @Size(max = 50, message = "Label must be at most 50 characters")
            String label,

            @NotBlank(message = "Name format is required")
            @Size(max = 100, message = "Name format must be at most 100 characters")
            String format,

            @Min(value = 1, message = "Count must be at least 1")
            @Max(value = 256, message = "Count must be at most 256")
            Integer count,

            @Min(value = 0, message = "Start counter must be at least 0")
            @Max(value = 999_999_999L, message = "Start counter must be at most 999999999")
            Long startCounter
    ) {
        public Group {
            if (startCounter == null) startCounter = 1L;
            if (label != null && label.isBlank()) label = null;
        }
    }
}
