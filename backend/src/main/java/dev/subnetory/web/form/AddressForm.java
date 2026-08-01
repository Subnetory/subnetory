package dev.subnetory.web.form;

import dev.subnetory.dto.AddressResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AddressForm {

    private Long id;

    @NotBlank(message = "L'adresse IP est obligatoire")
    @Pattern(
        regexp = "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$",
        message = "Format IPv4 invalide (ex : 192.168.1.10)"
    )
    private String address;

    @Size(max = 17, message = "17 caractères maximum")
    private String mac;

    @Size(max = 100, message = "100 caractères maximum")
    private String hostname;

    @Size(max = 500, message = "500 caractères maximum")
    private String description;

    @NotNull(message = "Le sous-réseau est obligatoire")
    private Long subnetId;

    /** Primitive boolean : false par défaut, géré correctement par la checkbox Thymeleaf. */
    private boolean temporary;

    public AddressForm() {}

    /** Pré-remplit le formulaire depuis une réponse service (édition).
     *  discovery_source non exposé — non modifiable depuis la GUI. */
    public static AddressForm from(AddressResponse addr) {
        AddressForm f = new AddressForm();
        f.id          = addr.id();
        f.address     = addr.address();
        f.mac         = addr.mac();
        f.hostname    = addr.hostname();
        f.description = addr.description();
        f.subnetId    = addr.subnetId();
        f.temporary   = addr.temporary();
        return f;
    }

    public Long getId()                    { return id; }
    public void setId(Long id)             { this.id = id; }

    public String getAddress()                   { return address; }
    public void setAddress(String address)       { this.address = address; }

    public String getMac()               { return mac; }
    public void setMac(String mac)       { this.mac = mac; }

    public String getHostname()                  { return hostname; }
    public void setHostname(String hostname)     { this.hostname = hostname; }

    public String getDescription()                   { return description; }
    public void setDescription(String description)   { this.description = description; }

    public Long getSubnetId()                { return subnetId; }
    public void setSubnetId(Long subnetId)   { this.subnetId = subnetId; }

    public boolean isTemporary()                 { return temporary; }
    public void setTemporary(boolean temporary)  { this.temporary = temporary; }
}
