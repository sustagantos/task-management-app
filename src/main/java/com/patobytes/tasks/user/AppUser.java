package com.patobytes.tasks.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    /**
     * The Entra {@code oid} claim. Keyed on this rather than email or UPN
     * because addresses change - renames, marriages, domain moves - and every
     * task the person owns would orphan behind a changed key.
     */
    @Column(name = "entra_oid", nullable = false, unique = true)
    private String entraOid;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected AppUser() {
        // for JPA
    }

    public AppUser(String entraOid, String email, String displayName) {
        this.id = UUID.randomUUID();
        this.entraOid = entraOid;
        this.email = email;
        this.displayName = displayName;
        this.createdAt = Instant.now();
        this.lastSeenAt = this.createdAt;
    }

    public void seen(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
        this.lastSeenAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEntraOid() {
        return entraOid;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
