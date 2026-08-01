package dev.subnetory.service;

import dev.subnetory.dto.DashboardStats;
import dev.subnetory.dto.SubnetUtilizationEntry;
import dev.subnetory.repository.AddressRepository;
import dev.subnetory.repository.NetworkContextRepository;
import dev.subnetory.repository.SiteRepository;
import dev.subnetory.repository.SubnetRepository;
import dev.subnetory.repository.SubnetRepository.SubnetUsageProjection;
import dev.subnetory.repository.VlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link DashboardService}.
 *
 * <p>Aucune base de données — tous les repositories sont mockés.
 * Les projections {@link SubnetUsageProjection} sont mockées avec Mockito.</p>
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock NetworkContextRepository contextRepository;
    @Mock SiteRepository           siteRepository;
    @Mock VlanRepository           vlanRepository;
    @Mock SubnetRepository         subnetRepository;
    @Mock AddressRepository        addressRepository;
    @Mock ContextAccessService     contextAccessService;

    DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(
                contextRepository, siteRepository, vlanRepository,
                subnetRepository, addressRepository, contextAccessService);
    }

    // ── Compteurs globaux ─────────────────────────────────────────────────

    @Test
    @DisplayName("Compteurs : tous à 0 quand les repositories sont vides")
    void stats_allZero_whenRepositoriesEmpty() {
        when(contextAccessService.allowedContextIds()).thenReturn(List.of());

        DashboardStats stats = service.getStats();

        assertThat(stats.totalContexts()).isZero();
        assertThat(stats.totalSites()).isZero();
        assertThat(stats.totalVlans()).isZero();
        assertThat(stats.totalSubnets()).isZero();
        assertThat(stats.totalAddresses()).isZero();
        assertThat(stats.topSubnets()).isEmpty();
    }

    @Test
    @DisplayName("Compteurs : les valeurs retournées par les repos sont propagées")
    void stats_counters_matchRepositoryValues() {
        List<Long> allowedIds = List.of(1L, 2L, 3L);
        when(contextAccessService.allowedContextIds()).thenReturn(allowedIds);
        when(siteRepository.countByContextIdIn(allowedIds)).thenReturn(7L);
        when(vlanRepository.countBySiteContextIdIn(allowedIds)).thenReturn(12L);
        when(subnetRepository.countByContextIdIn(allowedIds)).thenReturn(25L);
        when(addressRepository.countByContextIdIn(allowedIds)).thenReturn(150L);
        when(subnetRepository.findAllWithUsageCountByContextIds(allowedIds)).thenReturn(List.of());

        DashboardStats stats = service.getStats();

        assertThat(stats.totalContexts()).isEqualTo(3L);
        assertThat(stats.totalSites()).isEqualTo(7L);
        assertThat(stats.totalVlans()).isEqualTo(12L);
        assertThat(stats.totalSubnets()).isEqualTo(25L);
        assertThat(stats.totalAddresses()).isEqualTo(150L);
    }

    // ── Calcul utilisation ────────────────────────────────────────────────

    @Test
    @DisplayName("Utilisation : subnet /24 avec 127 adresses → 49 %")
    void utilizationPct_correctForPartiallyFilledSubnet() {
        // /24 : IpUtils.usableAddressCount("10.0.0.0/24") avec inclusiveHostCount=true → 256
        SubnetUsageProjection p = mockProjection(1L, "10.0.0.0/24", null, "Site A", "Default", 127L);
        stubUsageQuery(p);

        SubnetUtilizationEntry entry = service.getStats().topSubnets().get(0);

        assertThat(entry.capacity()).isEqualTo(256L);
        assertThat(entry.used()).isEqualTo(127L);
        assertThat(entry.available()).isEqualTo(129L);
        assertThat(entry.utilizationPct()).isEqualTo(49);
    }

    @Test
    @DisplayName("Utilisation : subnet sans adresse → 0 %, available = capacity")
    void utilizationPct_zero_for_emptySubnet() {
        SubnetUsageProjection p = mockProjection(2L, "192.168.0.0/24", "vide", "Site B", "Default", 0L);
        stubUsageQuery(p);

        SubnetUtilizationEntry entry = service.getStats().topSubnets().get(0);

        assertThat(entry.used()).isZero();
        assertThat(entry.utilizationPct()).isZero();
        assertThat(entry.available()).isEqualTo(entry.capacity());
    }

    @Test
    @DisplayName("Utilisation : subnet plein (used == capacity) → 100 %")
    void utilizationPct_hundredPercent_when_subnet_full() {
        // /30 : 4 adresses avec inclusiveHostCount=true
        SubnetUsageProjection p = mockProjection(3L, "10.1.0.0/30", null, "Site C", "Default", 4L);
        stubUsageQuery(p);

        SubnetUtilizationEntry entry = service.getStats().topSubnets().get(0);

        assertThat(entry.utilizationPct()).isEqualTo(100);
        assertThat(entry.available()).isZero();
    }

    @Test
    @DisplayName("Utilisation : used > capacity (données incohérentes) → pct borné à 100, available = 0")
    void utilizationPct_never_exceeds_100() {
        // /30 = 4 adresses ; on insère 6 (incohérent mais possible si contrainte manquante)
        SubnetUsageProjection p = mockProjection(4L, "10.2.0.0/30", null, "Site D", "Default", 6L);
        stubUsageQuery(p);

        SubnetUtilizationEntry entry = service.getStats().topSubnets().get(0);

        assertThat(entry.utilizationPct()).isEqualTo(100);
        assertThat(entry.available()).isZero();
    }

    @Test
    @DisplayName("safeCapacity : réseau invalide → 0 sans exception")
    void safeCapacity_invalidNetwork_returnsZero() {
        assertThat(DashboardService.safeCapacity("not-a-cidr")).isZero();
        assertThat(DashboardService.safeCapacity(null)).isZero();
        assertThat(DashboardService.safeCapacity("")).isZero();
    }

    @Test
    @DisplayName("safeCapacity : IPv4 valide → valeur correcte")
    void safeCapacity_validCidr_returnsExpected() {
        // /24 avec inclusiveHostCount=true → 256
        assertThat(DashboardService.safeCapacity("10.0.0.0/24")).isEqualTo(256L);
        // /32 → 1
        assertThat(DashboardService.safeCapacity("10.0.0.1/32")).isEqualTo(1L);
    }

    // ── Tri ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tri : subnets triés par utilizationPct DESC, puis used DESC")
    void topSubnets_sortedByUtilizationPctDesc_thenUsedDesc() {
        // Trois subnets : pct 80, 80, 60 — les deux à 80 % différenciés par used
        SubnetUsageProjection s1 = mockProjection(1L, "10.0.0.0/24", null, "S", "C", 205L); // 80 %, 205 used
        SubnetUsageProjection s2 = mockProjection(2L, "10.1.0.0/24", null, "S", "C", 154L); // 60 %, 154 used
        SubnetUsageProjection s3 = mockProjection(3L, "10.2.0.0/24", null, "S", "C", 210L); // 82 %, 210 used
        stubUsageQuery(s1, s2, s3);

        List<SubnetUtilizationEntry> top = service.getStats().topSubnets();

        // s3 (82%) > s1 (80%) > s2 (60%)
        assertThat(top).hasSize(3);
        assertThat(top.get(0).subnetId()).isEqualTo(3L);
        assertThat(top.get(1).subnetId()).isEqualTo(1L);
        assertThat(top.get(2).subnetId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Tri secondaire : même pct → trié par used DESC")
    void topSubnets_sameUtilizationPct_sortedByUsedDesc() {
        // Deux /24 (256 capacité), même pct → différenciés par used
        SubnetUsageProjection sA = mockProjection(10L, "10.0.0.0/24", null, "S", "C", 128L); // 50 %
        SubnetUsageProjection sB = mockProjection(20L, "10.1.0.0/24", null, "S", "C", 130L); // 50 %
        stubUsageQuery(sA, sB);

        List<SubnetUtilizationEntry> top = service.getStats().topSubnets();

        // sB a 130 used > sA 128 used → sB en premier
        assertThat(top.get(0).subnetId()).isEqualTo(20L);
        assertThat(top.get(1).subnetId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Limite : plus de 10 subnets → retourne exactement 10")
    void topSubnets_limitedTo10() {
        List<SubnetUsageProjection> projections = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            projections.add(mockProjection((long) i, "10." + i + ".0.0/24", null, "S", "C", (long) (i * 10)));
        }
        stubUsageQuery(projections.toArray(SubnetUsageProjection[]::new));

        List<SubnetUtilizationEntry> top = service.getStats().topSubnets();

        assertThat(top).hasSize(DashboardService.TOP_SUBNETS_LIMIT);
    }

    // ── Helpers privés ────────────────────────────────────────────────────

    private SubnetUsageProjection mockProjection(Long id, String network, String description,
                                                  String siteName, String contextName, Long usedCount) {
        SubnetUsageProjection p = mock(SubnetUsageProjection.class);
        when(p.getSubnetId()).thenReturn(id);
        when(p.getNetwork()).thenReturn(network);
        when(p.getDescription()).thenReturn(description);
        when(p.getSiteName()).thenReturn(siteName);
        when(p.getContextName()).thenReturn(contextName);
        when(p.getUsedCount()).thenReturn(usedCount);
        return p;
    }

    private void stubUsageQuery(SubnetUsageProjection... projections) {
        List<Long> allowedIds = List.of(1L);
        when(contextAccessService.allowedContextIds()).thenReturn(allowedIds);
        when(siteRepository.countByContextIdIn(allowedIds)).thenReturn(1L);
        when(vlanRepository.countBySiteContextIdIn(allowedIds)).thenReturn(0L);
        when(subnetRepository.countByContextIdIn(allowedIds)).thenReturn((long) projections.length);
        when(addressRepository.countByContextIdIn(allowedIds)).thenReturn(0L);
        when(subnetRepository.findAllWithUsageCountByContextIds(allowedIds)).thenReturn(List.of(projections));
    }
}
