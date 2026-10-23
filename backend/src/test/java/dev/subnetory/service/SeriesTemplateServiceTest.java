package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.SeriesTemplate;
import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesGroupsResponse;
import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.dto.SeriesTemplateDuplicateRequest;
import dev.subnetory.dto.SeriesTemplateExpandRequest;
import dev.subnetory.dto.SeriesTemplateExpansionResponse;
import dev.subnetory.dto.SeriesTemplateRequest;
import dev.subnetory.dto.SeriesTemplateResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.SeriesTemplateRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

/** Tests unitaires de {@link SeriesTemplateService} (repository, acces et moteur simules). */
@ExtendWith(MockitoExtension.class)
class SeriesTemplateServiceTest {

    private static final Long CONTEXT_ID = 3L;

    @Mock SeriesTemplateRepository repository;
    @Mock NetworkContextService contextService;
    @Mock ContextAccessService contextAccessService;
    @Mock SeriesReservationService seriesReservationService;

    SeriesTemplateService service;
    NetworkContext context;

    @BeforeEach
    void setUp() {
        JsonMapper mapper = JsonMapper.builder().build();
        service = new SeriesTemplateService(repository, contextService, contextAccessService,
                new BuiltinSeriesTemplates(mapper), seriesReservationService, mapper);
        context = new NetworkContext();
        context.setId(CONTEXT_ID);
    }

    // -------------------------------------------------------
    // list / get
    // -------------------------------------------------------

    @Test
    @DisplayName("list : presets puis templates du contexte demande (acces verifie)")
    void list_presetsThenContextTemplates() {
        SeriesTemplate custom = stored(10L, "Mon cluster");
        when(repository.findByContextIdOrderByNameAsc(CONTEXT_ID)).thenReturn(List.of(custom));

        List<SeriesTemplateResponse> result = service.list(CONTEXT_ID);

        verify(contextAccessService).requireAccess(CONTEXT_ID);
        assertThat(result).first().satisfies(r -> {
            assertThat(r.builtin()).isTrue();
            assertThat(r.key()).startsWith("builtin:");
            assertThat(r.id()).isNull();
            assertThat(r.contextId()).isNull();
        });
        assertThat(result).last().satisfies(r -> {
            assertThat(r.builtin()).isFalse();
            assertThat(r.key()).isEqualTo("custom:10");
            assertThat(r.contextId()).isEqualTo(CONTEXT_ID);
            assertThat(r.name()).isEqualTo("Mon cluster");
            assertThat(r.definition().groups()).hasSize(1);
        });
    }

    @Test
    @DisplayName("list sans contexte : templates de tous les contextes autorises")
    void list_allAllowedContexts() {
        when(contextAccessService.allowedContextIds()).thenReturn(List.of(1L, 2L));
        when(repository.findByContextIdInOrderByNameAsc(List.of(1L, 2L))).thenReturn(List.of());

        assertThat(service.list(null)).allMatch(SeriesTemplateResponse::builtin);
        verify(repository).findByContextIdInOrderByNameAsc(List.of(1L, 2L));
    }

    @Test
    @DisplayName("list sans contexte autorise : presets seulement, sans requete")
    void list_noAllowedContext() {
        when(contextAccessService.allowedContextIds()).thenReturn(List.of());

        assertThat(service.list(null)).isNotEmpty().allMatch(SeriesTemplateResponse::builtin);
        verify(repository, never()).findByContextIdInOrderByNameAsc(any());
    }

    @Test
    @DisplayName("get : preset, template personnalise, cle inconnue ou mal formee")
    void get_variants() {
        when(repository.findById(10L)).thenReturn(Optional.of(stored(10L, "Mon cluster")));
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.get("builtin:nutanix-hpe").builtin()).isTrue();
        assertThat(service.get("custom:10").name()).isEqualTo("Mon cluster");
        verify(contextAccessService).requireResourceAccess(CONTEXT_ID, "SeriesTemplate", 10L);

        assertThatThrownBy(() -> service.get("builtin:nope")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get("custom:99")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get("custom:abc")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get("whatever")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(null)).isInstanceOf(ResourceNotFoundException.class);
    }

    // -------------------------------------------------------
    // create / update / delete
    // -------------------------------------------------------

    @Test
    @DisplayName("create : nom nettoye, definition serialisee, auteur enregistre")
    void create_savesTrimmedNameAndDefinition() {
        when(contextService.getEntityById(CONTEXT_ID)).thenReturn(context);
        when(contextAccessService.currentUsername()).thenReturn("nahim");
        when(repository.existsByContextIdAndNameIgnoreCase(CONTEXT_ID, "Mon cluster")).thenReturn(false);
        when(repository.saveAndFlush(any(SeriesTemplate.class))).thenAnswer(inv -> {
            SeriesTemplate t = inv.getArgument(0);
            t.setId(11L);
            return t;
        });

        SeriesTemplateResponse response = service.create(new SeriesTemplateRequest(
                CONTEXT_ID, "  Mon cluster ", "  ", definition()));

        ArgumentCaptor<SeriesTemplate> captor = ArgumentCaptor.forClass(SeriesTemplate.class);
        verify(repository).saveAndFlush(captor.capture());
        SeriesTemplate saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("Mon cluster");
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.getCreatedBy()).isEqualTo("nahim");
        assertThat(saved.getModifiedBy()).isEqualTo("nahim");
        assertThat(saved.getDefinition()).contains("\"role\":\"HOST\"");
        assertThat(response.key()).isEqualTo("custom:11");
        assertThat(response.definition().groups().get(0).format()).isEqualTo("{cluster}-{role}-{nn}");
    }

    @Test
    @DisplayName("create : nom deja utilise dans le contexte -> ConflictException")
    void create_duplicateNameIsRejected() {
        when(contextService.getEntityById(CONTEXT_ID)).thenReturn(context);
        when(repository.existsByContextIdAndNameIgnoreCase(CONTEXT_ID, "Mon cluster")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new SeriesTemplateRequest(
                CONTEXT_ID, "Mon cluster", null, definition())))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create : creation concurrente du meme nom -> ConflictException")
    void create_concurrentDuplicateIsReportedAsConflict() {
        when(contextService.getEntityById(CONTEXT_ID)).thenReturn(context);
        when(repository.saveAndFlush(any(SeriesTemplate.class)))
                .thenThrow(new DataIntegrityViolationException("uq_series_templates_context_name"));

        assertThatThrownBy(() -> service.create(new SeriesTemplateRequest(
                CONTEXT_ID, "Mon cluster", null, definition())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("create : definition invalide refusee avant toute ecriture")
    void create_invalidDefinitionIsRejected() {
        when(contextService.getEntityById(CONTEXT_ID)).thenReturn(context);
        SeriesTemplateDefinition invalid = new SeriesTemplateDefinition(null, null, null, null,
                List.of(new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-HOST", null, null)));

        assertThatThrownBy(() -> service.create(new SeriesTemplateRequest(
                CONTEXT_ID, "Mon cluster", null, invalid)))
                .isInstanceOf(InvalidSeriesException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create : nom vide refuse")
    void create_blankNameIsRejected() {
        when(contextService.getEntityById(CONTEXT_ID)).thenReturn(context);

        assertThatThrownBy(() -> service.create(new SeriesTemplateRequest(
                CONTEXT_ID, "   ", null, definition())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("update : modifie le template, l'unicite ignore le template lui-meme")
    void update_changesTemplate() {
        SeriesTemplate existing = stored(10L, "Ancien nom");
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(contextAccessService.currentUsername()).thenReturn("autre");
        when(repository.existsByContextIdAndNameIgnoreCaseAndIdNot(CONTEXT_ID, "Nouveau", 10L))
                .thenReturn(false);
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        SeriesTemplateResponse response = service.update(10L,
                new SeriesTemplateRequest(CONTEXT_ID, "Nouveau", "Desc", definition()));

        assertThat(response.name()).isEqualTo("Nouveau");
        assertThat(response.description()).isEqualTo("Desc");
        assertThat(existing.getModifiedBy()).isEqualTo("autre");
        assertThat(existing.getCreatedBy()).isEqualTo("createur");
    }

    @Test
    @DisplayName("update : changer de contexte ou prendre un nom existant -> ConflictException")
    void update_rejectsContextMoveAndDuplicateName() {
        SeriesTemplate existing = stored(10L, "Ancien nom");
        when(repository.findById(10L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(10L,
                new SeriesTemplateRequest(4L, "Ancien nom", null, definition())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("another context");

        when(repository.existsByContextIdAndNameIgnoreCaseAndIdNot(CONTEXT_ID, "Pris", 10L)).thenReturn(true);
        assertThatThrownBy(() -> service.update(10L,
                new SeriesTemplateRequest(CONTEXT_ID, "Pris", null, definition())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("delete : supprime et renvoie le template supprime")
    void delete_removesAndReturnsTemplate() {
        SeriesTemplate existing = stored(10L, "Mon cluster");
        when(repository.findById(10L)).thenReturn(Optional.of(existing));

        SeriesTemplateResponse deleted = service.delete(10L);

        verify(repository).delete(existing);
        assertThat(deleted.id()).isEqualTo(10L);
        assertThat(deleted.name()).isEqualTo("Mon cluster");
    }

    @Test
    @DisplayName("delete / update : template inconnu -> ResourceNotFoundException")
    void unknownTemplateIsNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(99L,
                new SeriesTemplateRequest(CONTEXT_ID, "X", null, definition())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("definition stockee illisible -> IllegalStateException")
    void corruptedDefinitionIsReported() {
        SeriesTemplate corrupted = stored(10L, "Casse");
        corrupted.setDefinition("{not json");
        when(repository.findById(10L)).thenReturn(Optional.of(corrupted));

        assertThatThrownBy(() -> service.get("custom:10"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupted");
    }

    // -------------------------------------------------------
    // duplicate
    // -------------------------------------------------------

    @Test
    @DisplayName("duplicate : un preset devient un template personnalise du contexte")
    void duplicate_copiesPresetIntoContext() {
        when(contextService.getEntityById(CONTEXT_ID)).thenReturn(context);
        when(repository.saveAndFlush(any(SeriesTemplate.class))).thenAnswer(inv -> {
            SeriesTemplate t = inv.getArgument(0);
            t.setId(12L);
            return t;
        });

        SeriesTemplateResponse copy = service.duplicate("builtin:nutanix-hpe",
                new SeriesTemplateDuplicateRequest(CONTEXT_ID, "Nutanix HPE maison"));

        assertThat(copy.builtin()).isFalse();
        assertThat(copy.key()).isEqualTo("custom:12");
        assertThat(copy.name()).isEqualTo("Nutanix HPE maison");
        assertThat(copy.contextId()).isEqualTo(CONTEXT_ID);
        assertThat(copy.definition().groups()).extracting(SeriesTemplateDefinition.Group::role)
                .containsExactly("HOST", "CVM", "ILO");
    }

    // -------------------------------------------------------
    // expand
    // -------------------------------------------------------

    @Test
    @DisplayName("expand : calcule les series enchainees puis delegue l'apercu au moteur")
    void expand_delegatesPreviewToEngine() {
        SeriesGroupsResponse preview = new SeriesGroupsResponse("10.0.0.0/24", "block", true, 12, List.of());
        when(seriesReservationService.previewGroups(any(SeriesGroupsRequest.class))).thenReturn(preview);

        SeriesTemplateExpansionResponse response = service.expand(new SeriesTemplateExpandRequest(
                5L, "builtin:nutanix-hpe", 4, "NTNX01", null, "10.0.0.10", null, null, null,
                "Cluster prod", null));

        assertThat(response.preview()).isSameAs(preview);
        assertThat(response.request().subnetId()).isEqualTo(5L);
        assertThat(response.request().startIp()).isEqualTo("10.0.0.10");
        assertThat(response.request().groups()).hasSize(3);
        assertThat(response.request().groups().get(1).prefix()).isEqualTo("NTNX01-CVM-");
        assertThat(response.request().description()).isEqualTo("Cluster prod");
    }

    @Test
    @DisplayName("expand : template inconnu -> ResourceNotFoundException, parametres invalides -> InvalidSeriesException")
    void expand_errors() {
        assertThatThrownBy(() -> service.expand(new SeriesTemplateExpandRequest(
                5L, "builtin:nope", 4, "C1", null, "10.0.0.10", null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.expand(new SeriesTemplateExpandRequest(
                5L, "builtin:nutanix-hpe", null, "C1", null, "10.0.0.10", null, null, null, null, null)))
                .isInstanceOf(InvalidSeriesException.class);
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private static SeriesTemplateDefinition definition() {
        return new SeriesTemplateDefinition(null, null, null, null, List.of(
                new SeriesTemplateDefinition.Group("HOST", null, "{cluster}-{role}-{nn}", null, null)));
    }

    private SeriesTemplate stored(Long id, String name) {
        SeriesTemplate t = new SeriesTemplate();
        t.setId(id);
        t.setContext(context);
        t.setName(name);
        t.setCreatedBy("createur");
        t.setDefinition("{\"allocation\":\"groups-sequential\",\"groupGap\":0,\"ipStep\":1,"
                + "\"onOccupied\":\"block\",\"groups\":[{\"role\":\"HOST\","
                + "\"format\":\"{cluster}-{role}-{nn}\",\"startCounter\":1}]}");
        return t;
    }
}
