package dev.subnetory.repository;

import dev.subnetory.domain.Subnet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;

public interface SubnetRepository extends JpaRepository<Subnet, Long> {

    /**
     * Projection utilisée par {@link #findAllWithUsageCount()}.
     *
     * <p>Les alias SQL (snake_case) sont mappés aux getters (camelCase) par
     * Spring Data JPA via sa résolution de propriétés standard.</p>
     */
    interface SubnetUsageProjection {
        Long   getSubnetId();
        String getNetwork();
        String getDescription();
        String getSiteName();
        String getContextName();
        Long   getUsedCount();
    }

    /**
     * Retourne tous les subnets avec le nombre d'adresses IP qui leur sont assignées.
     *
     * <p>La jointure est LEFT JOIN : un subnet sans aucune adresse retourne
     * {@code used_count = 0}, pas NULL.</p>
     *
     * <p>Le cast {@code s.network::text} est nécessaire pour que le type natif
     * PostgreSQL {@code cidr} soit sérialisé en {@code String} dans la projection.</p>
     *
     * <p>Le tri final (par taux d'utilisation) est effectué en Java dans
     * {@link dev.subnetory.service.DashboardService} pour garder la logique
     * métier centralisée et éviter une dépendance sur {@code IpUtils} en SQL.</p>
     */
    @Query(value = """
            SELECT
                s.id            AS subnet_id,
                s.network::text AS network,
                s.description   AS description,
                si.name         AS site_name,
                c.name          AS context_name,
                COUNT(a.id)     AS used_count
            FROM subnets s
            JOIN sites si   ON si.id = s.site_id
            JOIN contexts c ON c.id  = s.context_id
            LEFT JOIN addresses a ON a.subnet_id = s.id
            GROUP BY s.id, s.network, s.description, si.name, c.name
            """, nativeQuery = true)
    List<SubnetUsageProjection> findAllWithUsageCount();

    @Query(value = """
            SELECT
                s.id            AS subnet_id,
                s.network::text AS network,
                s.description   AS description,
                si.name         AS site_name,
                c.name          AS context_name,
                COUNT(a.id)     AS used_count
            FROM subnets s
            JOIN sites si   ON si.id = s.site_id
            JOIN contexts c ON c.id  = s.context_id
            LEFT JOIN addresses a ON a.subnet_id = s.id
            WHERE s.context_id IN (:contextIds)
            GROUP BY s.id, s.network, s.description, si.name, c.name
            """, nativeQuery = true)
    List<SubnetUsageProjection> findAllWithUsageCountByContextIds(
            @Param("contextIds") Collection<Long> contextIds);

    // --- Paginés (GUI, API listée) ---

    Page<Subnet> findBySiteId(Long siteId, Pageable pageable);

    /**
     * Sous-réseaux d'un site, filtrés par le contexte propre du sous-réseau
     * (audit 03/08/2026, correctif BLOQUANT) : {@link #findBySiteId(Long, Pageable)}
     * seul n'autorise l'accès que via le contexte <em>actuel</em> du site, pas
     * via le {@code context_id} propre de chaque sous-réseau retourné. Comme
     * {@link dev.subnetory.service.SiteService#update} peut faire changer le
     * contexte d'un site, un sous-réseau existant peut rester associé à
     * l'ancien contexte (site_id inchangé, context_id non resynchronisé) —
     * sans ce filtre, un utilisateur limité au nouveau contexte du site
     * recevrait des sous-réseaux appartenant en réalité à l'ancien contexte.
     * Utilisée par l'API et le contrôleur web ; la variante sans filtre reste
     * disponible pour les usages internes qui ne dépendent pas d'un
     * utilisateur (aucun actuellement).
     */
    Page<Subnet> findBySiteIdAndContextIdIn(Long siteId, Collection<Long> contextIds, Pageable pageable);

    Page<Subnet> findByContextId(Long contextId, Pageable pageable);

    Page<Subnet> findByContextIdIn(Collection<Long> contextIds, Pageable pageable);

    Page<Subnet> findByParentId(Long parentId, Pageable pageable);

    /** Sous-réseaux d'un VLAN donné (navigation drill-down VLAN → subnets). */
    Page<Subnet> findByVlanId(Long vlanId, Pageable pageable);

    /**
     * Variante filtrée par contexte propre du sous-réseau, même raison que
     * {@link #findBySiteIdAndContextIdIn} ci-dessus (audit 03/08/2026,
     * correctif BLOQUANT) — s'applique ici à un VLAN déplacé vers un autre
     * site (donc potentiellement un autre contexte).
     */
    Page<Subnet> findByVlanIdAndContextIdIn(Long vlanId, Collection<Long> contextIds, Pageable pageable);

    /** Utilisé pour bloquer le changement de contexte d'un site qui a encore des sous-réseaux. */
    boolean existsBySiteId(Long siteId);

    /** Utilisé pour bloquer le changement de site d'un VLAN qui a encore des sous-réseaux. */
    boolean existsByVlanId(Long vlanId);

    // --- Non-paginés (export CSV — Sprint 2.8) ---

    /**
     * Retourne tous les subnets d'un site, sans pagination.
     * Utilisé par l'export CSV {@code GET /api/v1/subnets/export/csv?siteId=}.
     */
    List<Subnet> findBySiteId(Long siteId);

    /**
     * Variante filtrée par contexte propre du sous-réseau, même raison que
     * {@link #findBySiteIdAndContextIdIn} ci-dessus (audit 03/08/2026,
     * correctif BLOQUANT), pour l'export CSV.
     */
    List<Subnet> findBySiteIdAndContextIdIn(Long siteId, Collection<Long> contextIds);

    /**
     * Retourne tous les subnets d'un contexte, sans pagination.
     * Utilisé par l'export CSV {@code GET /api/v1/subnets/export/csv?contextId=}.
     */
    List<Subnet> findByContextId(Long contextId);

    List<Subnet> findByContextIdIn(Collection<Long> contextIds);

    long countByContextIdIn(Collection<Long> contextIds);

    /**
     * Vérifie l'unicité réseau+site avec cast explicite CIDR.
     * La méthode dérivée Spring Data génère "network = ?" qui échoue sur PostgreSQL
     * car le type CIDR ne peut pas être comparé directement à un VARCHAR.
     * Le CAST(:network AS cidr) force la conversion côté PostgreSQL.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM subnets
                WHERE network = CAST(:network AS cidr)
                  AND site_id = :siteId
            )
            """, nativeQuery = true)
    boolean existsByNetworkCidrAndSiteId(@Param("network") String network,
                                          @Param("siteId") Long siteId);

    /**
     * Trouve tous les subnets correspondant à un réseau CIDR donné.
     * Peut retourner plusieurs résultats si le même CIDR existe dans plusieurs
     * contextes ou sites (cas multi-VRF).
     * Utilisé par l'import CSV pour résoudre subnet_network → subnet_id.
     */
    @Query(value = "SELECT * FROM subnets WHERE network = CAST(:network AS cidr)",
           nativeQuery = true)
    List<Subnet> findAllByNetwork(@Param("network") String network);

    @Query(value = """
            SELECT * FROM subnets
            WHERE network = CAST(:network AS cidr)
              AND context_id IN (:contextIds)
            """, nativeQuery = true)
    List<Subnet> findAllByNetworkAndContextIds(
            @Param("network") String network,
            @Param("contextIds") Collection<Long> contextIds);
}
