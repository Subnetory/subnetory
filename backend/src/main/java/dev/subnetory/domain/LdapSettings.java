package dev.subnetory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ldap_settings")
public class LdapSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 512)
    private String url;

    @Column(name = "base_dn", nullable = false, length = 512)
    private String baseDn;

    @Column(name = "user_search_base", nullable = false, length = 512)
    private String userSearchBase;

    @Column(name = "user_search_filter", nullable = false, length = 512)
    private String userSearchFilter;

    @Column(name = "manager_dn", length = 512)
    private String managerDn;

    @Column(name = "manager_password_encrypted", columnDefinition = "TEXT")
    private String managerPasswordEncrypted;

    @Column(name = "default_role", nullable = false, length = 50)
    private String defaultRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public String getManagerPasswordEncrypted() { return managerPasswordEncrypted; }
    public void setManagerPasswordEncrypted(String managerPasswordEncrypted) { this.managerPasswordEncrypted = managerPasswordEncrypted; }
    public String getDefaultRole() { return defaultRole; }
    public void setDefaultRole(String defaultRole) { this.defaultRole = defaultRole; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
