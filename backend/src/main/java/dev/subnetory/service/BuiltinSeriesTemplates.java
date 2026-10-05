package dev.subnetory.service;

import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.util.SeriesTemplateExpander;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Presets de templates livres avec l'application ({@code classpath:series-templates/*.json}) :
 * globaux et en lecture seule, ils sont dupliquables en templates personnalises.
 *
 * <p>Chaque fichier a la forme {@code {key, name, description, definition}}. Tous sont
 * valides au demarrage : un preset invalide empeche l'application de demarrer plutot que
 * d'echouer a l'usage.</p>
 */
@Component
public class BuiltinSeriesTemplates {

    private static final String PATTERN = "classpath:series-templates/*.json";

    /** Preset charge. */
    public record Builtin(String key, String name, String description,
                          SeriesTemplateDefinition definition) {}

    /** Forme d'un fichier de preset. */
    public record PresetFile(String key, String name, String description,
                              SeriesTemplateDefinition definition) {}

    private final List<Builtin> templates;

    public BuiltinSeriesTemplates(ObjectMapper objectMapper) {
        this.templates = load(objectMapper);
    }

    public List<Builtin> all() {
        return templates;
    }

    public Optional<Builtin> find(String key) {
        return templates.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    private static List<Builtin> load(ObjectMapper objectMapper) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list the series template presets", e);
        }
        Set<String> keys = new HashSet<>();
        List<Builtin> loaded = Arrays.stream(resources).map(resource -> {
            PresetFile file;
            try (InputStream in = resource.getInputStream()) {
                file = objectMapper.readValue(in, PresetFile.class);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException(
                        "Invalid series template preset: " + resource.getFilename(), e);
            }
            if (file == null || file.key() == null || !file.key().matches("^[a-z0-9-]{1,60}$")
                    || file.name() == null || file.name().isBlank()) {
                throw new IllegalStateException(
                        "Invalid series template preset (key or name): " + resource.getFilename());
            }
            if (!keys.add(file.key())) {
                throw new IllegalStateException("Duplicate series template preset key: " + file.key());
            }
            try {
                SeriesTemplateExpander.validate(file.definition());
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Invalid series template preset: " + resource.getFilename()
                                + " (" + e.getMessage() + ")", e);
            }
            return new Builtin(file.key(), file.name(), file.description(), file.definition());
        }).sorted(Comparator.comparing(Builtin::name, String.CASE_INSENSITIVE_ORDER)).toList();
        return List.copyOf(loaded);
    }
}
