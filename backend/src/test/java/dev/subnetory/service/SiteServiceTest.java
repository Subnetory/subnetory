package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.repository.SiteRepository;
import dev.subnetory.repository.SubnetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression (audit du 03/08/2026, correctif BLOQUANT) : {@code
 * SiteService#update} laissait jusqu'ici changer librement le contexte d'un
 * site, sans se soucier des sous-reseaux existants sous ce site. Comme un
 * sous-reseau stocke son propre {@code context_id} (jamais resynchronise
 * apres coup), un site deplace vers un nouveau contexte laissait ses
 * sous-reseaux associes a l'ancien contexte — un utilisateur restreint au
 * nouveau contexte du site pouvait alors recevoir, via
 * {@code SubnetService#findBySite}, des sous-reseaux appartenant en realite
 * a l'ancien contexte (fuite entre perimetres).
 *
 * <p>Ce test ne necessite pas de contexte Spring ni de base de donnees : les
 * dependances de {@link SiteService} sont mockees.</p>
 */
@ExtendWith(MockitoExtension.class)
class SiteServiceTest {

    @Mock SiteRepository siteRepository;
    @Mock NetworkContextService contextService;
    @Mock ContextAccessService contextAccessService;
    @Mock SubnetRepository subnetRepository;

    SiteService service;

    NetworkContext contextA;
    NetworkContext contextB;
    Site site;

    @BeforeEach
    void setUp() {
        service = new SiteService(siteRepository, contextService, contextAccessService, subnetRepository);

        contextA = new NetworkContext();
        setId(contextA, 1L);

        contextB = new NetworkContext();
        setId(contextB, 2L);

        site = new Site();
        setId(site, 10L);
        site.setName("Site A");
        site.setCode("SITEA");
        site.setContext(contextA);

        lenient().when(siteRepository.findById(10L)).thenReturn(java.util.Optional.of(site));
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

    @Test
    void update_changingContext_withExistingSubnets_isRejected() {
        when(contextService.getEntityById(2L)).thenReturn(contextB);
        when(subnetRepository.existsBySiteId(10L)).thenReturn(true);

        var request = new SiteRequest("Site A", "SITEA", 2L);

        assertThatThrownBy(() -> service.update(10L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("sous-reseaux");
        verify(siteRepository, never()).save(any());
    }

    @Test
    void update_changingContext_withNoSubnets_succeeds() {
        when(contextService.getEntityById(2L)).thenReturn(contextB);
        when(subnetRepository.existsBySiteId(10L)).thenReturn(false);
        // Le code reste "SITEA" (inchange) : existsByCode() n'est jamais
        // appele, inutile de le stubber (UnnecessaryStubbingException).
        when(siteRepository.save(any(Site.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new SiteRequest("Site A", "SITEA", 2L);

        var response = service.update(10L, request);

        assertThat(response.contextId()).isEqualTo(2L);
    }

    @Test
    void update_keepingSameContext_neverChecksForSubnets_evenIfTheyExist() {
        // Renommer un site sans changer son contexte ne doit jamais etre
        // bloque par la presence de sous-reseaux : seul un changement reel
        // de contexte est concerne par ce garde-fou.
        when(contextService.getEntityById(1L)).thenReturn(contextA);
        when(siteRepository.save(any(Site.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new SiteRequest("Site A renamed", "SITEA", 1L);

        var response = service.update(10L, request);

        assertThat(response.name()).isEqualTo("Site A renamed");
        verify(subnetRepository, never()).existsBySiteId(any());
    }
}
