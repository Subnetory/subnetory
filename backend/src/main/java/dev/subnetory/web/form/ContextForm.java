package dev.subnetory.web.form;

import dev.subnetory.dto.NetworkContextResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Form bean pour la création et l'édition d'un contexte de routage (VRF).
 * Isolé des DTOs API pour ne pas coupler les deux couches.
 */
public class ContextForm {

    private Long id;

    @NotBlank(message = "Le nom est obligatoire")
    @Size(max = 100, message = "100 caractères maximum")
    private String name;

    @Size(max = 500, message = "500 caractères maximum")
    private String description;

    public ContextForm() {}

    /** Pré-remplit le formulaire depuis une réponse service (édition). */
    public static ContextForm from(NetworkContextResponse ctx) {
        ContextForm f = new ContextForm();
        f.id          = ctx.id();
        f.name        = ctx.name();
        f.description = ctx.description();
        return f;
    }

    public Long getId()                { return id; }
    public void setId(Long id)         { this.id = id; }

    public String getName()            { return name; }
    public void setName(String name)   { this.name = name; }

    public String getDescription()                 { return description; }
    public void setDescription(String description) { this.description = description; }
}
