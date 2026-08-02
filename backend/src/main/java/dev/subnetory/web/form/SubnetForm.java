package dev.subnetory.web.form;

import dev.subnetory.dto.SubnetResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SubnetForm {

    private Long id;

    @NotBlank(message = "{validation.network.required}")
    @Pattern(
        regexp = "^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$",
        message = "{validation.network.cidrFormat}"
    )
    private String network;

    @Size(max = 500, message = "{validation.size.max}")
    private String description;

    /** Vide ou blank => null géré par SubnetService.buildSubnet. */
    private String gateway;

    @NotNull(message = "{validation.field.contextRequired}")
    private Long contextId;

    @NotNull(message = "{validation.field.siteRequired}")
    private Long siteId;

    private Long vlanId;    // optionnel

    private Long parentId;  // optionnel

    public SubnetForm() {}

    public static SubnetForm from(SubnetResponse subnet) {
        SubnetForm f = new SubnetForm();
        f.id          = subnet.id();
        f.network     = subnet.network();
        f.description = subnet.description();
        f.gateway     = subnet.gateway();
        f.contextId   = subnet.contextId();
        f.siteId      = subnet.siteId();
        f.vlanId      = subnet.vlanId();
        f.parentId    = subnet.parentId();
        return f;
    }

    public Long getId()                    { return id; }
    public void setId(Long id)             { this.id = id; }

    public String getNetwork()             { return network; }
    public void setNetwork(String network) { this.network = network; }

    public String getDescription()                   { return description; }
    public void setDescription(String description)   { this.description = description; }

    public String getGateway()               { return gateway; }
    public void setGateway(String gateway)   { this.gateway = gateway; }

    public Long getContextId()                   { return contextId; }
    public void setContextId(Long contextId)     { this.contextId = contextId; }

    public Long getSiteId()                  { return siteId; }
    public void setSiteId(Long siteId)       { this.siteId = siteId; }

    public Long getVlanId()                  { return vlanId; }
    public void setVlanId(Long vlanId)       { this.vlanId = vlanId; }

    public Long getParentId()                { return parentId; }
    public void setParentId(Long parentId)   { this.parentId = parentId; }
}
