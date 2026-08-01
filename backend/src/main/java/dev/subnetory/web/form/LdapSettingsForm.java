package dev.subnetory.web.form;

import java.util.HashSet;
import java.util.Set;

public class LdapSettingsForm {

    private boolean enabled;
    private String url;
    private String baseDn;
    private String userSearchBase;
    private String userSearchFilter;
    private String managerDn;
    private String managerPassword;
    private Set<String> defaultRoles = new HashSet<>();
    private boolean clearManagerPassword;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBaseDn() { return baseDn; }
    public void setBaseDn(String baseDn) { this.baseDn = baseDn; }
    public String getUserSearchBase() { return userSearchBase; }
    public void setUserSearchBase(String userSearchBase) { this.userSearchBase = userSearchBase; }
    public String getUserSearchFilter() { return userSearchFilter; }
    public void setUserSearchFilter(String userSearchFilter) { this.userSearchFilter = userSearchFilter; }
    public String getManagerDn() { return managerDn; }
    public void setManagerDn(String managerDn) { this.managerDn = managerDn; }
    public String getManagerPassword() { return managerPassword; }
    public void setManagerPassword(String managerPassword) { this.managerPassword = managerPassword; }
    public Set<String> getDefaultRoles() { return defaultRoles; }
    public void setDefaultRoles(Set<String> defaultRoles) {
        this.defaultRoles = defaultRoles == null ? new HashSet<>() : defaultRoles;
    }
    public String getDefaultRole() {
        return defaultRoles.stream().findFirst().orElse(null);
    }
    public void setDefaultRole(String defaultRole) {
        this.defaultRoles = defaultRole == null || defaultRole.isBlank()
                ? new HashSet<>()
                : new HashSet<>(Set.of(defaultRole));
    }
    public boolean isClearManagerPassword() { return clearManagerPassword; }
    public void setClearManagerPassword(boolean clearManagerPassword) { this.clearManagerPassword = clearManagerPassword; }
}
