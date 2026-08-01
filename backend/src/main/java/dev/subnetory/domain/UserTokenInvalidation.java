package dev.subnetory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_token_invalidations")
public class UserTokenInvalidation {

    @Id
    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "not_before", nullable = false)
    private OffsetDateTime notBefore;

    @Column(name = "invalidated_at", nullable = false)
    private OffsetDateTime invalidatedAt;

    @Column(name = "invalidated_by", nullable = false, length = 100)
    private String invalidatedBy;

    @Column(nullable = false, length = 50)
    private String reason;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public OffsetDateTime getNotBefore() { return notBefore; }
    public void setNotBefore(OffsetDateTime notBefore) { this.notBefore = notBefore; }

    public OffsetDateTime getInvalidatedAt() { return invalidatedAt; }
    public void setInvalidatedAt(OffsetDateTime invalidatedAt) { this.invalidatedAt = invalidatedAt; }

    public String getInvalidatedBy() { return invalidatedBy; }
    public void setInvalidatedBy(String invalidatedBy) { this.invalidatedBy = invalidatedBy; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
