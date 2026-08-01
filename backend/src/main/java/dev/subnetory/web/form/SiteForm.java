package dev.subnetory.web.form;

import dev.subnetory.dto.SiteResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class SiteForm {

    private Long id;

    @NotBlank(message = "Le nom est obligatoire")
    @Size(max = 100, message = "100 caractères maximum")
    private String name;

    @NotBlank(message = "Le code est obligatoire")
    @Size(max = 20, message = "20 caractères maximum")
    private String code;

    @NotNull(message = "Le contexte est obligatoire")
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
