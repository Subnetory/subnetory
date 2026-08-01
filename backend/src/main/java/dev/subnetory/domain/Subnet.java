package dev.subnetory.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "subnets")
public class Subnet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Réseau au format CIDR, ex: "192.168.1.0/24".
     * Stocké en type PostgreSQL `cidr` natif. Le cast String → cidr est géré
     * automatiquement par le driver JDBC grâce à `stringtype=unspecified`
     * dans l'URL de connexion. IpUtils gère la validation côté Java.
     */
    @Column(name = "network", nullable = false, columnDefinition = "cidr")
    private String network;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "inet")
    private String gateway;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "context_id", nullable = false)
    private NetworkContext context;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vlan_id")
    private Vlan vlan;

    /** Sous-réseau parent (ex-surnet de v1). Auto-référence nullable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Subnet parent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Verrouillage optimiste (audit du 31/07/2026) : deux admins qui
     * modifient le meme sous-reseau en meme temps provoquaient un
     * ecrasement silencieux (dernier "save" gagne). Voir {@link Address#version}
     * pour le detail du mecanisme.
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

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getGateway() { return gateway; }
    public void setGateway(String gateway) { this.gateway = gateway; }

    public NetworkContext getContext() { return context; }
    public void setContext(NetworkContext context) { this.context = context; }

    public Site getSite() { return site; }
    public void setSite(Site site) { this.site = site; }

    public Vlan getVlan() { return vlan; }
    public void setVlan(Vlan vlan) { this.vlan = vlan; }

    public Subnet getParent() { return parent; }
    public void setParent(Subnet parent) { this.parent = parent; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public Long getVersion() { return version; }
}
