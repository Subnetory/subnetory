package dev.subnetory.web.form;

import dev.subnetory.dto.VlanResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class VlanForm {

    private Long id;

    @Size(max = 100, message = "100 caractères maximum")
    private String name;

    @NotNull(message = "Le VLAN ID est obligatoire")
    @Min(value = 0,    message = "Le VLAN ID doit être compris entre 0 et 4094")
    @Max(value = 4094, message = "Le VLAN ID doit être compris entre 0 et 4094")
    private Integer vid;

    @NotNull(message = "Le site est obligatoire")
    private Long siteId;

    public VlanForm() {}

    public static VlanForm from(VlanResponse vlan) {
        VlanForm f = new VlanForm();
        f.id     = vlan.id();
        f.name   = vlan.name();
        f.vid    = vlan.vid();
        f.siteId = vlan.siteId();
        return f;
    }

    public Long getId()              { return id; }
    public void setId(Long id)       { this.id = id; }

    public String getName()          { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getVid()              { return vid; }
    public void setVid(Integer vid)      { this.vid = vid; }

    public Long getSiteId()              { return siteId; }
    public void setSiteId(Long siteId)   { this.siteId = siteId; }
}
