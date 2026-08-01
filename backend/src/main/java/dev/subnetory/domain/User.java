package dev.subnetory.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    /** BCrypt hash. Nullable si le compte est authentifié via LDAP (Phase 2). */
    @Column(length = 255)
    private String password;

    @Column(length = 255)
    private String email;

    @Column(name = "auth_type", nullable = false, length = 20)
    private String authType = "LOCAL";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    /** Secret TOTP chiffre (AES/GCM via SecretCipherService), jamais stocke en clair. */
    @Column(name = "mfa_secret_encrypted", columnDefinition = "TEXT")
    private String mfaSecretEncrypted;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    /**
     * Perimetre de donnees autorise pour les comptes non administrateurs.
     *
     * <p>Un ensemble vide signifie "aucun contexte" (deny by default).
     * ROLE_ADMIN est traite comme administrateur global par ContextAccessService.</p>
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_contexts",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "context_id"))
    private Set<NetworkContext> allowedContexts = new HashSet<>();

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

    // --- getters / setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public boolean isMfaEnabled() { return mfaEnabled; }
    public void setMfaEnabled(boolean mfaEnabled) { this.mfaEnabled = mfaEnabled; }

    public String getMfaSecretEncrypted() { return mfaSecretEncrypted; }
    public void setMfaSecretEncrypted(String mfaSecretEncrypted) {
        this.mfaSecretEncrypted = mfaSecretEncrypted;
    }

    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }

    public Set<NetworkContext> getAllowedContexts() { return allowedContexts; }
    public void setAllowedContexts(Set<NetworkContext> allowedContexts) {
        this.allowedContexts = allowedContexts;
    }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
