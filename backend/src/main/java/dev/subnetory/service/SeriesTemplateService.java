package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.SeriesTemplate;
import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.dto.SeriesTemplateDuplicateRequest;
import dev.subnetory.dto.SeriesTemplateExpandRequest;
import dev.subnetory.dto.SeriesTemplateExpansionResponse;
import dev.subnetory.dto.SeriesTemplateRequest;
import dev.subnetory.dto.SeriesTemplateResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.SeriesTemplateRepository;
import dev.subnetory.util.SeriesTemplateExpander;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Templates de reservation en serie : presets livres (globaux, lecture seule) et templates
 * personnalises par contexte reseau.
 *
 * <p>Un template est un simple document ({@link SeriesTemplateDefinition}) : son application
 * ({@link #expand}) le transforme en series enchainees puis s'appuie sur le moteur de
 * {@link SeriesReservationService} pour l'apercu et la creation. Les controles d'acces au
 * contexte sont ceux de {@link ContextAccessService}.</p>
 */
@Service
@Transactional(readOnly = true)
public class SeriesTemplateService {

    static final String BUILTIN_PREFIX = "builtin:";
    static final String CUSTOM_PREFIX = "custom:";

    private final SeriesTemplateRepository repository;
    private final NetworkContextService contextService;
    private final ContextAccessService contextAccessService;
    private final BuiltinSeriesTemplates builtins;
    private final SeriesReservationService seriesReservationService;
    private final ObjectMapper objectMapper;

    public SeriesTemplateService(SeriesTemplateRepository repository,
                                 NetworkContextService contextService,
                                 ContextAccessService contextAccessService,
                                 BuiltinSeriesTemplates builtins,
                                 SeriesReservationService seriesReservationService,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.contextService = contextService;
        this.contextAccessService = contextAccessService;
        this.builtins = builtins;
        this.seriesReservationService = seriesReservationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Presets, puis templates personnalises. Avec {@code contextId} : ceux de ce contexte
     * (acces verifie) ; sinon ceux de tous les contextes autorises.
     */
    public List<SeriesTemplateResponse> list(Long contextId) {
        List<SeriesTemplateResponse> result = new ArrayList<>();
        builtins.all().forEach(b -> result.add(toResponse(b)));
        List<SeriesTemplate> customs;
        if (contextId != null) {
            contextAccessService.requireAccess(contextId);
            customs = repository.findByContextIdOrderByNameAsc(contextId);
        } else {
            List<Long> allowed = contextAccessService.allowedContextIds();
            customs = allowed.isEmpty() ? List.of() : repository.findByContextIdInOrderByNameAsc(allowed);
        }
        customs.forEach(t -> result.add(toResponse(t)));
        return result;
    }

    public SeriesTemplateResponse get(String key) {
        if (key != null && key.startsWith(BUILTIN_PREFIX)) {
            return builtins.find(key.substring(BUILTIN_PREFIX.length()))
                    .map(this::toResponse)
                    .orElseThrow(() -> new ResourceNotFoundException("SeriesTemplate", key));
        }
        return toResponse(getEntity(customId(key)));
    }

    @Transactional
    public SeriesTemplateResponse create(SeriesTemplateRequest request) {
        NetworkContext context = contextService.getEntityById(request.contextId());
        return toResponse(save(new SeriesTemplate(), context, request.name(), request.description(),
                request.definition()));
    }

    @Transactional
    public SeriesTemplateResponse update(Long id, SeriesTemplateRequest request) {
        SeriesTemplate template = getEntity(id);
        if (!template.getContext().getId().equals(request.contextId())) {
            throw new ConflictException("A template cannot be moved to another context. "
                    + "Duplicate it into the other context instead.");
        }
        return toResponse(save(template, template.getContext(), request.name(),
                request.description(), request.definition()));
    }

    /** Supprime un template personnalise et le renvoie (pour l'audit). */
    @Transactional
    public SeriesTemplateResponse delete(Long id) {
        SeriesTemplate template = getEntity(id);
        SeriesTemplateResponse deleted = toResponse(template);
        repository.delete(template);
        return deleted;
    }

    /** Copie un preset ou un template personnalise en template personnalise du contexte. */
    @Transactional
    public SeriesTemplateResponse duplicate(String key, SeriesTemplateDuplicateRequest request) {
        SeriesTemplateResponse source = get(key);
        NetworkContext context = contextService.getEntityById(request.contextId());
        return toResponse(save(new SeriesTemplate(), context, request.name(), source.description(),
                source.definition()));
    }

    /** Applique un template a un sous-reseau et renvoie la demande calculee et son apercu. */
    public SeriesTemplateExpansionResponse expand(SeriesTemplateExpandRequest request) {
        SeriesTemplateDefinition definition = get(request.key()).definition();
        SeriesGroupsRequest groups = SeriesTemplateExpander.expand(request.subnetId(), definition,
                new SeriesTemplateExpander.Params(request.cluster(), request.site(), request.nodes(),
                        request.startIp(), request.gap(), request.step(), request.occupiedMode(),
                        request.description(), request.temporary()));
        return new SeriesTemplateExpansionResponse(groups, seriesReservationService.previewGroups(groups));
    }

    // -------------------------------------------------------
    // Internal
    // -------------------------------------------------------

    private SeriesTemplate save(SeriesTemplate template, NetworkContext context, String rawName,
                                String description, SeriesTemplateDefinition definition) {
        SeriesTemplateExpander.validate(definition);
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new ConflictException("The template name is required.");
        }
        boolean taken = template.getId() == null
                ? repository.existsByContextIdAndNameIgnoreCase(context.getId(), name)
                : repository.existsByContextIdAndNameIgnoreCaseAndIdNot(
                        context.getId(), name, template.getId());
        if (taken) {
            throw new ConflictException("A template named '" + name + "' already exists in this context.");
        }
        template.setContext(context);
        template.setName(name);
        template.setDescription(description == null || description.isBlank() ? null : description.trim());
        template.setDefinition(write(definition));
        String user = contextAccessService.currentUsername();
        if (template.getId() == null) {
            template.setCreatedBy(user);
        }
        template.setModifiedBy(user);
        try {
            return repository.saveAndFlush(template);
        } catch (DataIntegrityViolationException e) {
            // Creation concurrente du meme nom : la contrainte unique est le filet de securite.
            throw new ConflictException("A template named '" + name + "' already exists in this context.");
        }
    }

    private SeriesTemplate getEntity(Long id) {
        SeriesTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SeriesTemplate", id));
        contextAccessService.requireResourceAccess(template.getContext().getId(), "SeriesTemplate", id);
        return template;
    }

    private static Long customId(String key) {
        if (key != null && key.startsWith(CUSTOM_PREFIX)) {
            try {
                return Long.parseLong(key.substring(CUSTOM_PREFIX.length()));
            } catch (NumberFormatException ignored) {
                // cle mal formee : traitee comme un template inconnu
            }
        }
        throw new ResourceNotFoundException("SeriesTemplate", key);
    }

    private String write(SeriesTemplateDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize the template definition", e);
        }
    }

    private SeriesTemplateDefinition read(SeriesTemplate template) {
        try {
            return objectMapper.readValue(template.getDefinition(), SeriesTemplateDefinition.class);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "The stored definition of template " + template.getId() + " is corrupted", e);
        }
    }

    private SeriesTemplateResponse toResponse(BuiltinSeriesTemplates.Builtin b) {
        return new SeriesTemplateResponse(BUILTIN_PREFIX + b.key(), true, null, null, b.name(),
                b.description(), b.definition(), null);
    }

    private SeriesTemplateResponse toResponse(SeriesTemplate t) {
        return new SeriesTemplateResponse(CUSTOM_PREFIX + t.getId(), false, t.getId(),
                t.getContext().getId(), t.getName(), t.getDescription(), read(t), t.getUpdatedAt());
    }
}
