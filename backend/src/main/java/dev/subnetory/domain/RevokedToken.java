package dev.subnetory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * JWT revoque avant son expiration naturelle.
 *
 * <p>Le jti est l'identifiant standard du token JWT. Une entree n'a plus
 * d'utilite apres expiresAt et peut donc etre purgee automatiquement.</p>
 */
@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {

    @Id
    @Column(length = 36, nullable = false)
    private String jti;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false, updatable = false)
    private OffsetDateTime revokedAt;

    @Column(nullable = false, length = 50)
    private String reason;

    @PrePersist
    void onCreate() {
        if (revokedAt == null) {
            revokedAt = OffsetDateTime.now();
        }
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
