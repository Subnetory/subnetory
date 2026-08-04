package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.domain.Vlan;
import dev.subnetory.dto.VlanRequest;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.SubnetRepository;
import dev.subnetory.repository.VlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression (audit du 03/08/2026, correctif BLOQUANT) : {@code
 * VlanService#update} laissait jusqu'ici changer librement le site d'un
 * VLAN, sans se soucier des sous-reseaux existants rattaches a ce VLAN.
 * Comme un sous-reseau stocke son propre {@code site_id}/{@code context_id}
 * (jamais resynchronise apres coup), un VLAN deplace vers un autre site
 * laissait ses sous-reseaux associes a l'ancien site/contexte — meme classe
 * de fuite entre perimetres que {@code SiteServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class VlanServiceTest {

    @Mock VlanRepository vlanRepository;
    @Mock SiteService siteService;
    @Mock ContextAccessService contextAccessService;
    @Mock SubnetRepository subnetRepository;

    VlanService service;

    NetworkContext context;
    Site siteX;
    Site siteY;
    Vlan vlan;

    @BeforeEach
    void setUp() {
        service = new VlanService(vlanRepository, siteService, contextAccessService, subnetRepository);

        context = new NetworkContext();
        setId(context, 1L);

        siteX = new Site();
        setId(siteX, 10L);
        siteX.setContext(context);

        siteY = new Site();
        setId(siteY, 20L);
        siteY.setContext(context);

        vlan = new Vlan();
        setId(vlan, 100L);
        vlan.setName("VLAN 100");
        vlan.setVid((short) 100);
        vlan.setSite(siteX);

        lenient().when(vlanRepository.findById(100L)).thenReturn(java.util.Optional.of(vlan));
        lenient().when(contextAccessService.canAccess(any())).thenReturn(true);
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

    // -------------------------------------------------------
    // Canal lateral 404/409 sur create() (correctif securite FAIBLE, audit
    // 04/08/2026) : meme motif que SiteServiceTest — verifier l'unicite du
    // VID AVANT le controle d'acces au site parent permettait de distinguer,
    // pour un site hors perimetre, un VID deja utilise (409) d'un VID libre
    // (404) — revelant quels VID sont deja pris sur un site inaccessible.
    // -------------------------------------------------------

    @Test
    void create_siteInaccessible_throwsNotFound_evenIfVidAlreadyExists() {
        when(siteService.getEntityById(30L))
                .thenThrow(new ResourceNotFoundException("Site", 30L));

        var request = new VlanRequest("VLAN 100", 100, 30L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResourceNotFoundException.class);

        // Point cle de la regression : l'unicite du VID n'est jamais
        // verifiee tant que l'acces au site parent n'est pas confirme.
        verify(vlanRepository, never()).existsByVidAndSiteId(anyShort(), any());
        verify(vlanRepository, never()).save(any());
    }

    @Test
    void create_authorizesSiteBeforeCheckingVidUniqueness() {
        when(siteService.getEntityById(10L)).thenReturn(siteX);
        when(vlanRepository.existsByVidAndSiteId((short) 200, 10L)).thenReturn(false);
        when(vlanRepository.save(any(Vlan.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new VlanRequest("VLAN 200", 200, 10L);
        service.create(request);

        InOrder order = inOrder(siteService, vlanRepository);
        order.verify(siteService).getEntityById(10L);
        order.verify(vlanRepository).existsByVidAndSiteId((short) 200, 10L);
    }

    @Test
    void update_changingSite_withExistingSubnets_isRejected() {
        when(subnetRepository.existsByVlanId(100L)).thenReturn(true);

        var request = new VlanRequest("VLAN 100", 100, 20L);

        assertThatThrownBy(() -> service.update(100L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("sous-reseaux");
        verify(vlanRepository, never()).save(any());
    }

    @Test
    void update_changingSite_withNoSubnets_succeeds() {
        when(subnetRepository.existsByVlanId(100L)).thenReturn(false);
        when(siteService.getEntityById(20L)).thenReturn(siteY);
        when(vlanRepository.save(any(Vlan.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new VlanRequest("VLAN 100", 100, 20L);

        var response = service.update(100L, request);

        assertThat(response.siteId()).isEqualTo(20L);
    }

    @Test
    void update_keepingSameSite_neverChecksForSubnets_evenIfTheyExist() {
        when(siteService.getEntityById(10L)).thenReturn(siteX);
        when(vlanRepository.save(any(Vlan.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new VlanRequest("VLAN 100 renamed", 100, 10L);

        var response = service.update(100L, request);

        assertThat(response.name()).isEqualTo("VLAN 100 renamed");
        verify(subnetRepository, never()).existsByVlanId(any());
    }
}
