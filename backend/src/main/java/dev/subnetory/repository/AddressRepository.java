package dev.subnetory.repository;

import dev.subnetory.domain.Address;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

/**
 * Repository adresses IP.
 *
 * <p>Étend {@link JpaSpecificationExecutor} pour la recherche multi-critères
 * dynamique (hostname, MAC, q, siteId, contextId, subnetId).</p>
 */
public interface AddressRepository
        extends JpaRepository<Address, Long>, JpaSpecificationExecutor<Address> {

    Page<Address> findBySubnetId(Long subnetId, Pageable pageable);

    /**
     * Utilisé pour bloquer le changement de contexte/site/réseau CIDR d'un
     * sous-réseau qui a encore des adresses (audit 03/08/2026, correctif
     * MOYEN, même patron que {@code SubnetRepository#existsBySiteId} pour
     * le fix Site/VLAN du même jour) : voir
     * {@link dev.subnetory.service.SubnetService#update}.
     */
    boolean existsBySubnetId(Long subnetId);

    /**
     * Variante filtrée par contexte propre de l'adresse (audit 03/08/2026,
     * correctif BLOQUANT, même raison que
     * {@link SubnetRepository#findBySiteIdAndContextIdIn}) : non appelée
     * actuellement ({@link dev.subnetory.service.AddressService#findBySubnet}
     * n'est câblée sur aucun contrôleur), mais ajoutée par précaution avant
     * qu'elle ne le devienne sans que ce correctif soit reproduit.
     */
    Page<Address> findBySubnetIdAndContextIdIn(Long subnetId, Collection<Long> contextIds, Pageable pageable);

    List<Address> findByHostname(String hostname);

    List<Address> findByHostnameAndContextIdIn(String hostname, Collection<Long> contextIds);

    long countByContextIdIn(Collection<Long> contextIds);

    /**
     * Recherche par IP exacte via la fonction PostgreSQL {@code host()},
     * qui extrait l'IP sans le préfixe CIDR.
     */
    @Query(value = """
            SELECT * FROM addresses
            WHERE host(address) = :ip
            """, nativeQuery = true)
    Optional<Address> findByIpExact(@Param("ip") String ip);

    @Query(value = """
            SELECT * FROM addresses
            WHERE host(address) = :ip
              AND context_id IN (:contextIds)
            ORDER BY id
            """, nativeQuery = true)
    List<Address> findAllByIpExactAndContextIdIn(@Param("ip") String ip,
                                                 @Param("contextIds") Collection<Long> contextIds);

    @Query(value = """
            SELECT * FROM addresses
            WHERE host(address) = :ip
              AND subnet_id = :subnetId
            """, nativeQuery = true)
    Optional<Address> findByIpExactAndSubnetId(@Param("ip") String ip,
                                               @Param("subnetId") Long subnetId);

    /**
     * Met a jour uniquement {@code last_seen_at}/{@code updated_at}, sans
     * passer par le cycle de vie complet de l'entite JPA (audit du
     * 31/07/2026). Utilise volontairement une requete cibl e plutot que
     * {@code save()} pour les scans/imports qui ne font que "constater" une
     * adresse deja connue sans modification metier reelle : {@code save()}
     * sur l'entite complete incrementerait {@link Address#version} a chaque
     * scan, ce qui ferait entrer en conflit une edition manuelle en cours
     * avec le prochain passage de scan automatique -- alors qu'il n'y a
     * aucune modification concurrente reelle a signaler dans ce cas.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Address a SET a.lastSeenAt = :now, a.updatedAt = :now WHERE a.id = :id")
    void touchLastSeen(@Param("id") Long id, @Param("now") java.time.OffsetDateTime now);

    /**
     * Retourne les premieres IPv4 disponibles d'un subnet avec SQL natif PostgreSQL.
     *
     * <p>La requete ne genere jamais toute la plage du CIDR. Elle transforme les
     * adresses occupees en offsets, detecte les trous avec {@code lead()}, puis
     * developpe avec {@code generate_series} uniquement les premiers trous utiles
     * pour satisfaire la limite demandee.</p>
     *
     * <p>Regles conservees : network/broadcast exclus jusqu'a /30, deux adresses
     * candidates en /31, adresse unique candidate en /32, gateway reservee seulement
     * lorsqu'elle appartient au subnet.</p>
     */
    @Query(value = """
            WITH bounds AS (
                SELECT
                    s.network,
                    CASE
                        WHEN masklen(s.network) >= 31
                            THEN set_masklen(network(s.network)::inet, 32)
                        ELSE set_masklen(network(s.network)::inet, 32) + 1
                    END AS first_ip,
                    CASE
                        WHEN masklen(s.network) >= 31
                            THEN set_masklen(broadcast(s.network), 32)
                        ELSE set_masklen(broadcast(s.network), 32) - 1
                    END AS last_ip,
                    CASE
                        WHEN s.gateway IS NOT NULL
                             AND family(s.gateway) = 4
                             AND s.gateway <<= s.network
                            THEN set_masklen(s.gateway, 32)
                        ELSE NULL
                    END AS reserved_gateway
                FROM subnets s
                WHERE s.id = :subnetId
                  AND family(s.network) = 4
            ),
            occupied AS (
                SELECT DISTINCT (a.address - b.first_ip) AS ip_offset
                FROM addresses a
                CROSS JOIN bounds b
                WHERE a.subnet_id = :subnetId
                  AND a.address <<= b.network
                  -- addresses.address conserve un prefixe hote /32 en IPv4
                  -- via chk_addresses_host_prefix, requis pour cet ordre inet.
                  AND a.address BETWEEN b.first_ip AND b.last_ip

                UNION

                SELECT reserved_gateway - first_ip
                FROM bounds
                WHERE reserved_gateway IS NOT NULL
                  AND reserved_gateway BETWEEN first_ip AND last_ip
            ),
            markers AS (
                SELECT CAST(-1 AS bigint) AS ip_offset

                UNION

                SELECT ip_offset
                FROM occupied

                UNION

                SELECT (last_ip - first_ip) + 1
                FROM bounds
            ),
            gaps AS (
                SELECT
                    ip_offset + 1 AS gap_start,
                    lead(ip_offset) OVER (ORDER BY ip_offset) - 1 AS gap_end
                FROM markers
            ),
            open_gaps AS (
                SELECT
                    gap_start,
                    gap_end,
                    gap_end - gap_start + 1 AS gap_size
                FROM gaps
                WHERE gap_end >= gap_start
            ),
            ranked_gaps AS (
                SELECT
                    gap_start,
                    gap_end,
                    COALESCE(
                        CAST(
                            sum(gap_size) OVER (
                                ORDER BY gap_start
                                ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                            ) AS bigint
                        ),
                        CAST(0 AS bigint)
                    ) AS available_before
                FROM open_gaps
            ),
            candidates AS (
                SELECT b.first_ip + generated.ip_offset AS candidate
                FROM bounds b
                JOIN ranked_gaps g ON g.available_before < :limit
                CROSS JOIN LATERAL generate_series(
                    g.gap_start,
                    LEAST(
                        g.gap_end,
                        g.gap_start + (:limit - g.available_before) - 1
                    )
                ) AS generated(ip_offset)
            )
            SELECT host(candidate)
            FROM candidates
            ORDER BY candidate
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findAvailableIps(@Param("subnetId") Long subnetId,
                                  @Param("limit") int limit);

    List<Address> findByHostnameStartingWithOrderByHostnameDesc(String prefix);
}
