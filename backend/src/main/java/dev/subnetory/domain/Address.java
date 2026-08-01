package dev.subnetory.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "addresses")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Adresse IP hôte, ex: "192.168.1.10".
     * Stockée en type PostgreSQL {@code inet} natif.
     * Cast String → inet automatique via {@code stringtype=unspecified}.
     */
    @Column(nullable = false, columnDefinition = "inet")
    private String address;

    /** Adresse MAC au format "aa:bb:cc:dd:ee:ff". Type {@code macaddr} natif. */
    @Column(columnDefinition = "macaddr")
    private String mac;

    @Column(length = 100)
    private String hostname;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "context_id", nullable = false)
    private NetworkContext context;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subnet_id", nullable = false)
    private Subnet subnet;

    @Column(name = "modified_by", length = 100)
    private String modifiedBy;

    @Column(name = "is_temporary", nullable = false)
    private boolean temporary = false;

    /**
     * Dernière fois où cette IP a été observée (scan, import, ping...).
     * Mis à jour à chaque bulk-upsert, même si l'entrée n'est pas modifiée.
     * NULL si jamais observée par un outil automatique (saisie manuelle pure).
     */
    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    /**
     * Origine de la création de cette entrée.
     * Défini à la création, jamais modifié ensuite.
     * Valeurs : manual, api, csv, nmap, arp-scan, dns.
     */
    @Column(name = "discovery_source", nullable = false, length = 20)
    private String discoverySource = "manual";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Verrouillage optimiste (audit du 31/07/2026). Gere entierement par
     * Hibernate : incremente a chaque UPDATE reussi, provoque une
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
     * si la version en base a change depuis le chargement de l'entite (deux
     * admins qui editent la meme adresse en meme temps).
     *
     * <p>Attention : {@link dev.subnetory.service.AddressService#bulkUpsert}
     * touche {@code lastSeenAt} sur CHAQUE scan/import, y compris sans
     * modification metier reelle. Ce chemin utilise volontairement une
     * requete de mise a jour ciblee ({@code AddressRepository.touchLastSeen})
     * plutot qu'un {@code save()} de l'entite complete, pour ne PAS
     * incrementer cette version a chaque scan -- sinon une edition manuelle
     * entrerait en conflit avec le prochain scan automatique, meme sans
     * changement concurrent reel.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getMac() { return mac; }
    public void setMac(String mac) { this.mac = mac; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public NetworkContext getContext() { return context; }
    public void setContext(NetworkContext context) { this.context = context; }

    public Site getSite() { return site; }
    public void setSite(Site site) { this.site = site; }

    public Subnet getSubnet() { return subnet; }
    public void setSubnet(Subnet subnet) { this.subnet = subnet; }

    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }

    public boolean isTemporary() { return temporary; }
    public void setTemporary(boolean temporary) { this.temporary = temporary; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public String getDiscoverySource() { return discoverySource; }
    public void setDiscoverySource(String discoverySource) { this.discoverySource = discoverySource; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public Long getVersion() { return version; }
}
