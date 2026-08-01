package dev.subnetory.web.form;

import java.util.HashSet;
import java.util.Set;

/**
 * Form bean pour la mise à jour des rôles d'un utilisateur.
 * La validation métier (rôle vide, anti-lockout) est dans UserAdminService.
 */
public class UserRoleForm {

    private Set<Long> roleIds = new HashSet<>();

    public Set<Long> getRoleIds()            { return roleIds; }
    public void setRoleIds(Set<Long> roleIds) { this.roleIds = roleIds; }
}
