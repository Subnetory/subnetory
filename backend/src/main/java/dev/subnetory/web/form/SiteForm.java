package dev.subnetory.web.form;

import dev.subnetory.dto.SiteResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SiteForm {

    private Long id;

    @NotBlank(message = "{validation.field.nameRequired}")
    @Size(max = 100, message = "{validation.size.max}")
    private String name;

    @NotBlank(message = "{validation.field.codeRequired}")
    @Size(max = 20, message = "{validation.size.max}")
    // Aligne sur SiteRequest (API REST) - audit 02/08/2026, correctif ELEVEE :
    // le formulaire web n'imposait jusqu'ici aucun format de code, contrairement
    // a l'API qui exige des majuscules/chiffres/_/-. Un code invalide saisi via
    // le web (espaces, accents, minuscules) passait la validation Bean Validation
    // sans erreur claire, pour echouer plus loin de facon moins comprehensible.
    @Pattern(regexp = "^[A-Z0-9_-]+$", message = "{validation.field.codePattern}")
    private String code;

    @NotNull(message = "{validation.field.contextRequired}")
    private Long contextId;

    public SiteForm() {}

    public static SiteForm from(SiteResponse site) {
        SiteForm f = new SiteForm();
        f.id        = site.id();
        f.name      = site.name();
        f.code      = site.code();
        f.contextId = site.contextId();
        return f;
    }

    public Long getId()                  { return id; }
    public void setId(Long id)           { this.id = id; }

    public String getName()              { return name; }
    public void setName(String name)     { this.name = name; }

    public String getCode()              { return code; }
    public void setCode(String code)     { this.code = code; }

    public Long getContextId()               { return contextId; }
    public void setContextId(Long contextId) { this.contextId = contextId; }
}
