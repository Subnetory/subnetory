package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.domain.Subnet;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.repository.AddressRepository;
import dev.subnetory.repository.SubnetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression (audit du 02/08/2026, correctif ELEVEE) : {@code buildSubnet}
 * ne verifiait jusqu'ici que l'appartenance du parent au meme contexte,
 * jamais que le CIDR de l'enfant soit reellement contenu dans celui du
 * parent, ni l'absence de cycle dans la chaine de parente (y compris un
 * sous-reseau choisi comme son propre parent). Un utilisateur pouvait donc
 * construire une hierarchie incoherente (parent 10.0.0.0/24, enfant
 * 192.168.1.0/28, aucun lien reel) ou circulaire.
 *
 * <p>Ce test ne necessite pas de contexte Spring ni de base de donnees : les
 * dependances de {@link SubnetService} sont mockees, seule la logique pure
 * de {@code buildSubnet} / {@code isContainedInParent} est exercee via
 * {@link SubnetService#create} et {@link SubnetService#update}.</p>
 */
@ExtendWith(MockitoExtension.class)
class SubnetServiceTest {

    @Mock SubnetRepository subnetRepository;
    @Mock AddressRepository addressRepository;
    @Mock NetworkContextService contextService;
    @Mock SiteService siteService;
    @Mock VlanService vlanService;
    @Mock ContextAccessService contextAccessService;

    SubnetService service;

    NetworkContext context;
    NetworkContext otherContext;
    Site site;

    @BeforeEach
    void setUp() {
        service = new SubnetService(subnetRepository, addressRepository, contextService, siteService, vlanService, contextAccessService);

        context = new NetworkContext();
        setId(context, 1L);

        otherContext = new NetworkContext();
        setId(otherContext, 2L);

        site = new Site();
        setId(site, 10L);
        site.setContext(context);

        lenient().when(contextService.getEntityById(1L)).thenReturn(context);
        lenient().when(contextService.getEntityById(2L)).thenReturn(otherContext);
        lenient().when(siteService.getEntityById(10L)).thenReturn(site);
    }

    private Subnet subnetWith(Long id, String network, Subnet parent) {
        Subnet s = new Subnet();
        setId(s, id);
        s.setNetwork(network);
        s.setContext(context);
        s.setSite(site);
        s.setParent(parent);
        return s;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void create_rejectsParentThatDoesNotContainChildCidr() {
        // Parent 10.0.0.0/24 ne contient pas du tout 192.168.1.0/28 : aucun
        // recouvrement d'adresses entre les deux blocs.
        Subnet parent = subnetWith(100L, "10.0.0.0/24", null);
        when(subnetRepository.findById(100L)).thenReturn(java.util.Optional.of(parent));

        var request = new SubnetRequest("192.168.1.0/28", null, null, 1L, 10L, null, 100L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("n'est pas contenu");
    }

    @Test
    void create_rejectsChildWiderThanParent() {
        // Prefixe enfant (/16) moins specifique que le parent (/24) : ne
        // peut pas etre "contenu", meme si les adresses de depart coincident.
        Subnet parent = subnetWith(100L, "10.0.0.0/24", null);
        when(subnetRepository.findById(100L)).thenReturn(java.util.Optional.of(parent));

        var request = new SubnetRequest("10.0.0.0/16", null, null, 1L, 10L, null, 100L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("n'est pas contenu");
    }

    @Test
    void create_acceptsChildFullyContainedInParent() {
        Subnet parent = subnetWith(100L, "10.0.0.0/16", null);
        when(subnetRepository.findById(100L)).thenReturn(java.util.Optional.of(parent));
        when(subnetRepository.save(any(Subnet.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new SubnetRequest("10.0.5.0/24", null, null, 1L, 10L, null, 100L);

        var response = service.create(request);

        assertThat(response.network()).isEqualTo("10.0.5.0/24");
        assertThat(response.parentId()).isEqualTo(100L);
    }

    @Test
    void update_rejectsSubnetAsItsOwnParent() {
        Subnet existing = subnetWith(5L, "10.0.0.0/24", null);
        when(subnetRepository.findById(5L)).thenReturn(java.util.Optional.of(existing));

        // parentId == id du sous-reseau lui-meme
        var request = new SubnetRequest("10.0.0.0/24", null, null, 1L, 10L, null, 5L);

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("propre parent");
    }

    @Test
    void update_rejectsIndirectCycleThroughAncestorChain() {
        // A (id=1) -> B (id=2) -> C (id=3) deja en place. Tenter de faire de
        // C le parent de A creerait un cycle A -> C -> B -> A.
        Subnet a = subnetWith(1L, "10.0.0.0/16", null);
        Subnet b = subnetWith(2L, "10.0.0.0/20", a);
        Subnet c = subnetWith(3L, "10.0.0.0/24", b);

        when(subnetRepository.findById(1L)).thenReturn(java.util.Optional.of(a));
        when(subnetRepository.findById(3L)).thenReturn(java.util.Optional.of(c));

        // A prend C comme parent -> en remontant C -> B -> A, on retombe sur A (id=1).
        // Le CIDR propose (10.0.0.0/25) est volontairement contenu dans celui
        // de C (10.0.0.0/24) pour isoler la detection de cycle du controle de
        // confinement CIDR, teste separement ci-dessus.
        var request = new SubnetRequest("10.0.0.0/25", null, null, 1L, 10L, null, 3L);

        assertThatThrownBy(() -> service.update(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cycle");
    }

    /**
     * Regression (audit du 03/08/2026, correctif BLOQUANT) : {@code
     * findBySite}/{@code findByVlan} n'autorisaient l'acces qu'au contexte
     * <em>actuel</em> du site/VLAN parent, jamais au contexte propre de
     * chaque sous-reseau retourne. Un sous-reseau reste associe a un ancien
     * contexte (site deplace sans que ses sous-reseaux ne le suivent)
     * pouvait donc etre renvoye a un utilisateur qui n'a pas acces a cet
     * ancien contexte. Ces deux methodes doivent desormais filtrer par le
     * contexte propre du sous-reseau, pas seulement par celui du parent.
     */
    @Test
    void findBySite_filtersByOwnContextOfEachSubnet_notJustParentSiteContext() {
        Pageable pageable = PageRequest.of(0, 20);
        when(contextAccessService.allowedContextIds()).thenReturn(List.of(1L));
        when(subnetRepository.findBySiteIdAndContextIdIn(eq(10L), eq(List.of(1L)), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(subnetWith(1L, "10.0.0.0/24", null))));

        var page = service.findBySite(10L, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(subnetRepository, never()).findBySiteId(anyLong(), any());
    }

    @Test
    void findBySite_noAllowedContexts_returnsEmptyWithoutQuerying() {
        Pageable pageable = PageRequest.of(0, 20);
        when(contextAccessService.allowedContextIds()).thenReturn(List.of());

        var page = service.findBySite(10L, pageable);

        assertThat(page.getContent()).isEmpty();
        verify(subnetRepository, never()).findBySiteIdAndContextIdIn(any(), any(), any());
    }

    @Test
    void findByVlan_filtersByOwnContextOfEachSubnet_notJustParentVlanContext() {
        Pageable pageable = PageRequest.of(0, 20);
        when(vlanService.getEntityById(50L)).thenReturn(new dev.subnetory.domain.Vlan());
        when(contextAccessService.allowedContextIds()).thenReturn(List.of(1L));
        when(subnetRepository.findByVlanIdAndContextIdIn(eq(50L), eq(List.of(1L)), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(subnetWith(1L, "10.0.0.0/24", null))));

        var page = service.findByVlan(50L, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(subnetRepository, never()).findByVlanId(anyLong(), any());
    }

    /**
     * Regression (audit du 03/08/2026, correctif MOYEN) : {@code
     * SubnetService#update} laissait jusqu'ici changer librement le
     * contexte, le site ou le reseau CIDR d'un sous-reseau, sans se soucier
     * des adresses existantes (qui stockent leur propre context_id/site_id,
     * jamais resynchronise) ni des sous-reseaux enfants (dont le containment
     * CIDR et le contexte ne sont valides qu'au moment ou <em>eux</em>
     * choisissent ce parent, jamais retroactivement).
     */
    @Test
    void update_changingContext_withExistingAddresses_isRejected() {
        Subnet existing = subnetWith(5L, "10.0.0.0/24", null);
        when(subnetRepository.findById(5L)).thenReturn(java.util.Optional.of(existing));
        when(addressRepository.existsBySubnetId(5L)).thenReturn(true);

        var request = new SubnetRequest("10.0.0.0/24", null, null, 2L, 10L, null, null);

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("adresses");
        verify(subnetRepository, never()).save(any());
    }

    @Test
    void update_changingNetwork_withExistingAddresses_isRejected() {
        Subnet existing = subnetWith(5L, "10.0.0.0/24", null);
        when(subnetRepository.findById(5L)).thenReturn(java.util.Optional.of(existing));
        when(addressRepository.existsBySubnetId(5L)).thenReturn(true);

        var request = new SubnetRequest("10.0.1.0/24", null, null, 1L, 10L, null, null);

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("adresses");
        verify(subnetRepository, never()).save(any());
    }

    @Test
    void update_changingContext_withChildSubnets_isRejected() {
        Subnet existing = subnetWith(5L, "10.0.0.0/24", null);
        when(subnetRepository.findById(5L)).thenReturn(java.util.Optional.of(existing));
        when(subnetRepository.existsByParentId(5L)).thenReturn(true);

        var request = new SubnetRequest("10.0.0.0/24", null, null, 2L, 10L, null, null);

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("enfants");
        verify(subnetRepository, never()).save(any());
    }

    @Test
    void update_changingNetwork_withChildSubnets_isRejected() {
        Subnet existing = subnetWith(5L, "10.0.0.0/24", null);
        when(subnetRepository.findById(5L)).thenReturn(java.util.Optional.of(existing));
        when(subnetRepository.existsByParentId(5L)).thenReturn(true);

        var request = new SubnetRequest("10.0.0.0/25", null, null, 1L, 10L, null, null);

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("enfants");
        verify(subnetRepository, never()).save(any());
    }

    @Test
    void update_changingSiteOnly_withChildSubnets_isNotBlockedByChildGuard() {
        // Un changement de site seul (contexte et CIDR inchanges) n'affecte
        // pas la coherence des enfants (containment CIDR et contexte) : seul
        // le garde-fou "adresses" pourrait s'appliquer, pas celui des enfants.
        Site otherSite = new Site();
        setId(otherSite, 20L);
        otherSite.setContext(context);
        lenient().when(siteService.getEntityById(20L)).thenReturn(otherSite);

        Subnet existing = subnetWith(5L, "10.0.0.0/24", null);
        when(subnetRepository.findById(5L)).thenReturn(java.util.Optional.of(existing));
        when(subnetRepository.save(any(Subnet.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new SubnetRequest("10.0.0.0/24", null, null, 1L, 20L, null, null);

        var response = service.update(5L, request);

        assertThat(response.siteId()).isEqualTo(20L);
        verify(subnetRepository, never()).existsByParentId(any());
    }

    @Test
    void update_noRelevantChange_neverChecksGuards_evenIfAddressesOrChildrenExist() {
        // Renommer/decrire un sous-reseau sans toucher contexte/site/reseau
        // ne doit jamais etre bloque par la presence d'adresses ou d'enfants :
        // seul un changement reel de l'un de ces trois champs est concerne.
        Subnet existing = subnetWith(5L, "10.0.0.0/24", null);
        when(subnetRepository.findById(5L)).thenReturn(java.util.Optional.of(existing));
        when(subnetRepository.save(any(Subnet.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new SubnetRequest("10.0.0.0/24", "renamed", null, 1L, 10L, null, null);

        var response = service.update(5L, request);

        assertThat(response.description()).isEqualTo("renamed");
        verify(addressRepository, never()).existsBySubnetId(any());
        verify(subnetRepository, never()).existsByParentId(any());
    }
}
