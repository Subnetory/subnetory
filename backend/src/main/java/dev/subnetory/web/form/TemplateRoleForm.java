package dev.subnetory.web.form;

import jakarta.validation.constraints.Size;

/**
 * Destination d'un rôle d'un template « un sous-réseau par rôle » sur la page de réservation :
 * son sous-réseau et, facultativement, son IP de départ. Sans sous-réseau, le rôle suit le
 * sous-réseau choisi plus haut ; sans IP de départ, le premier bloc libre est utilisé.
 */
public class TemplateRoleForm {

    @Size(max = 30, message = "{validation.size.max}")
    private String role;

    private Long subnetId;

    @Size(max = 15, message = "{validation.size.max}")
    private String startIp;

    public String getRole()                { return role; }
    public void setRole(String role)       { this.role = role; }

    public Long getSubnetId()              { return subnetId; }
    public void setSubnetId(Long subnetId) { this.subnetId = subnetId; }

    public String getStartIp()               { return startIp; }
    public void setStartIp(String startIp)   { this.startIp = startIp; }

    /** Vrai si l'utilisateur a renseigné quelque chose pour ce rôle. */
    public boolean isFilled() {
        return role != null && !role.isBlank()
                && (subnetId != null || (startIp != null && !startIp.isBlank()));
    }
}
