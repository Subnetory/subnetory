package dev.subnetory.service;

import dev.subnetory.dto.DashboardStats;
import dev.subnetory.dto.SubnetUtilizationEntry;
import dev.subnetory.repository.AddressRepository;
import dev.subnetory.repository.NetworkContextRepository;
import dev.subnetory.repository.SiteRepository;
import dev.subnetory.repository.SubnetRepository;
import dev.subnetory.repository.SubnetRepository.SubnetUsageProjection;
import dev.subnetory.repository.VlanRepository;
import dev.subnetory.util.IpUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service de calcul des statistiques du dashboard.
 *
 * <h3>Compteurs globaux</h3>
 * <p>Cinq appels {@code count()} — un par table principale. Aucune requête
 * personnalisée : Spring Data génère {@code SELECT COUNT(*) FROM table}.</p>
 *
 * <h3>Top subnets</h3>
 * <p>Une seule requête native {@link SubnetRepository#findAllWithUsageCount()}
 * avec LEFT JOIN + GROUP BY. Le tri et la limite sont appliqués en Java :</p>
 * <ol>
 *   <li>La capacité est calculée via {@link IpUtils#usableAddressCount} —
 *       logique métier centralisée, non dupliquée en SQL.</li>
 *   <li>Tri par {@code utilizationPct DESC}, puis {@code used DESC}.</li>
 *   <li>Limite à 10 entrées.</li>
 * </ol>
 *
 * <h3>Définition de la capacité</h3>
 * <p>{@code IpUtils.usableAddressCount} utilise {@code setInclusiveHostCount(true)} :
 * la capacité inclut l'adresse réseau et le broadcast. C'est la « capacité
 * théorique » du bloc CIDR, cohérente avec le comportement existant d'IpUtils.</p>
 *
 * <h3>Gestion des cas limites</h3>
 * <ul>
 *   <li>Subnet IPv6 ou CIDR invalide → capacité = 0, utilisation = 0 %.</li>
 *   <li>{@code used > capacity} (données incohérentes) → {@code available = 0},
 *       {@code utilizationPct} borné à 100.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    static final int TOP_SUBNETS_LIMIT = 10;

    private final NetworkContextRepository contextRepository;
    private final SiteRepository           siteRepository;
    private final VlanRepository           vlanRepository;
    private final SubnetRepository         subnetRepository;
    private final AddressRepository        addressRepository;
    private final ContextAccessService     contextAccessService;

    public DashboardService(NetworkContextRepository contextRepository,
                            SiteRepository           siteRepository,
                            VlanRepository           vlanRepository,
                            SubnetRepository         subnetRepository,
                            AddressRepository        addressRepository,
                            ContextAccessService     contextAccessService) {
        this.contextRepository = contextRepository;
        this.siteRepository    = siteRepository;
        this.vlanRepository    = vlanRepository;
        this.subnetRepository  = subnetRepository;
        this.addressRepository = addressRepository;
        this.contextAccessService = contextAccessService;
    }

    /**
     * Calcule et retourne les statistiques globales pour le dashboard.
     *
     * @return {@link DashboardStats} avec compteurs + top 10 subnets
     */
    public DashboardStats getStats() {
        return getStats(null);
    }

    public DashboardStats getStats(Long activeContextId) {
        List<Long> allowedIds = contextAccessService.allowedContextIds();
        if (activeContextId != null) {
            contextAccessService.requireAccess(activeContextId);
            allowedIds = List.of(activeContextId);
        }

        if (allowedIds.isEmpty()) {
            return new DashboardStats(0, 0, 0, 0, 0, List.of());
        }

        long totalContexts  = allowedIds.size();
        long totalSites     = siteRepository.countByContextIdIn(allowedIds);
        long totalVlans     = vlanRepository.countBySiteContextIdIn(allowedIds);
        long totalSubnets   = subnetRepository.countByContextIdIn(allowedIds);
        long totalAddresses = addressRepository.countByContextIdIn(allowedIds);

        List<SubnetUsageProjection> raw =
                subnetRepository.findAllWithUsageCountByContextIds(allowedIds);

        List<SubnetUtilizationEntry> topSubnets = raw.stream()
                .map(this::toUtilizationEntry)
                .sorted((a, b) -> {
                    int cmp = Integer.compare(b.utilizationPct(), a.utilizationPct());
                    if (cmp != 0) return cmp;
                    return Long.compare(b.used(), a.used());
                })
                .limit(TOP_SUBNETS_LIMIT)
                .toList();

        return new DashboardStats(
                totalContexts,
                totalSites,
                totalVlans,
                totalSubnets,
                totalAddresses,
                topSubnets
        );
    }

    // ── private helpers ────────────────────────────────────────────────────

    private SubnetUtilizationEntry toUtilizationEntry(SubnetUsageProjection p) {
        long capacity = safeCapacity(p.getNetwork());
        long used     = p.getUsedCount() != null ? p.getUsedCount() : 0L;
        long available    = Math.max(0L, capacity - used);
        int  utilizationPct = capacity > 0
                ? (int) Math.min(100L, (used * 100L) / capacity)
                : 0;

        return new SubnetUtilizationEntry(
                p.getSubnetId(),
                p.getNetwork(),
                p.getDescription(),
                p.getSiteName(),
                p.getContextName(),
                capacity,
                used,
                available,
                utilizationPct
        );
    }

    /**
     * Calcule la capacité d'un CIDR via IpUtils.
     *
     * <p>Retourne 0 si le CIDR est invalide (IPv6 ou format non reconnu par IpUtils)
     * plutôt que de propager une exception vers la vue.</p>
     */
    static long safeCapacity(String network) {
        if (network == null) return 0L;
        try {
            return IpUtils.usableAddressCount(network);
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }
}
