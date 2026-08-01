package dev.subnetory.web.form;

import java.util.HashSet;
import java.util.Set;

public class UserCreateForm {

    private String username;
    private String email;
    private String password;
    private boolean enabled = true;
    private Set<Long> roleIds = new HashSet<>();
    private Set<Long> contextIds = new HashSet<>();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(Set<Long> roleIds) {
        this.roleIds = roleIds == null ? new HashSet<>() : roleIds;
    }

    public Set<Long> getContextIds() {
        return contextIds;
    }

    public void setContextIds(Set<Long> contextIds) {
        this.contextIds = contextIds == null ? new HashSet<>() : contextIds;
    }
}
