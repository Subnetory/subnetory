package dev.subnetory.util;

import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.exception.InvalidSeriesException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforme un template ({@link SeriesTemplateDefinition}) et ses parametres d'utilisation
 * (nom du cluster, nombre de noeuds, IP de depart...) en une demande de series enchainees
 * ({@link SeriesGroupsRequest}). Aucun acces base de donnees : l'apercu et la creation
 * s'appuient ensuite sur le moteur de series, qui calcule les adresses et les conflits.
 *
 * <p>Mode {@code groups-sequential} : les groupes se suivent a partir de l'IP de depart
 * (ecart configurable). Mode {@code interleaved} : chaque groupe demarre a l'adresse
 * suivante de la premiere et avance par pas de (nombre de groupes x pas), de sorte que les
 * adresses d'un noeud se suivent (hote, CVM, IPMI, puis noeud suivant).</p>
 */
public final class SeriesTemplateExpander {

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Pattern COUNTER = Pattern.compile("n{1,9}");
    private static final Pattern VALUE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$");
    private static final Set<String> VARIABLES = Set.of("cluster", "site", "role");
    private static final long IPV4_MAX = 0xFFFFFFFFL;

    private SeriesTemplateExpander() {}

    /**
     * Parametres d'utilisation d'un template. Les valeurs absentes ({@code gap}, {@code step},
     * {@code occupiedMode}) retombent sur celles du template.
     */
    public record Params(String cluster, String site, Integer nodes, String startIp,
                         Integer gap, Integer step, String occupiedMode,
                         String description, Boolean temporary) {}

    /** Modele de nom decoupe : prefixe, largeur du compteur et suffixe (variables non resolues). */
    private record Format(String prefix, int width, String suffix) {}

    /**
     * Controle semantique d'une definition : formats de nom (variables connues, un seul
     * compteur), coherence des champs.
     *
     * @throws InvalidSeriesException si la definition est invalide
     */
    public static void validate(SeriesTemplateDefinition definition) {
        if (definition == null) {
            throw new InvalidSeriesException("The template definition is required.");
        }
        if (!SeriesTemplateDefinition.SEQUENTIAL.equals(definition.allocation())
                && !SeriesTemplateDefinition.INTERLEAVED.equals(definition.allocation())) {
            throw new InvalidSeriesException(
                    "The allocation must be groups-sequential or interleaved.");
        }
        checkRange("groupGap", definition.groupGap(), 0, SeriesPlanner.MAX_STEP);
        checkRange("ipStep", definition.ipStep(), 1, SeriesPlanner.MAX_STEP);
        if (!"block".equals(definition.onOccupied()) && !"skip".equals(definition.onOccupied())) {
            throw new InvalidSeriesException("onOccupied must be block or skip.");
        }
        List<SeriesTemplateDefinition.Group> groups = definition.groups();
        if (groups == null || groups.isEmpty()) {
            throw new InvalidSeriesException("A template needs at least one group.");
        }
        if (groups.size() > 10) {
            throw new InvalidSeriesException("A template has at most 10 groups.");
        }
        for (SeriesTemplateDefinition.Group group : groups) {
            if (group == null) {
                throw new InvalidSeriesException("A template group must not be empty.");
            }
            if (group.role() == null || !group.role().matches("^[A-Za-z0-9_-]{1,30}$")) {
                throw new InvalidSeriesException(
                        "A group role must be 1 to 30 letters, digits, '-' or '_'.");
            }
            if (group.label() != null && group.label().length() > 50) {
                throw new InvalidSeriesException("A group label has at most 50 characters.");
            }
            if (group.count() != null) {
                checkRange("count", group.count(), 1, SeriesPlanner.MAX_COUNT);
            }
            if (group.startCounter() != null && (group.startCounter() < 0
                    || group.startCounter() > SeriesPlanner.MAX_COUNTER)) {
                throw new InvalidSeriesException("The start counter must be between 0 and "
                        + SeriesPlanner.MAX_COUNTER + ".");
            }
            parseFormat(group.format());
        }
    }

    /**
     * Calcule la demande de series enchainees d'un template pour des parametres donnes.
     *
     * @throws InvalidSeriesException si le template ou les parametres sont invalides
     */
    public static SeriesGroupsRequest expand(Long subnetId, SeriesTemplateDefinition definition,
                                             Params params) {
        validate(definition);
        if (params == null) {
            throw new InvalidSeriesException("The template parameters are required.");
        }
        int gap = params.gap() != null ? params.gap() : definition.groupGap();
        int step = params.step() != null ? params.step() : definition.ipStep();
        String mode = params.occupiedMode() != null ? params.occupiedMode() : definition.onOccupied();
        checkRange("gap", gap, 0, SeriesPlanner.MAX_STEP);
        checkRange("step", step, 1, SeriesPlanner.MAX_STEP);
        if (!"block".equals(mode) && !"skip".equals(mode)) {
            throw new InvalidSeriesException("The occupied-address mode must be block or skip.");
        }
        boolean interleaved = SeriesTemplateDefinition.INTERLEAVED.equals(definition.allocation());
        if (interleaved && "skip".equals(mode)) {
            throw new InvalidSeriesException(
                    "Interleaved allocation does not support skipping occupied addresses.");
        }
        String startIp = params.startIp() == null ? "" : params.startIp().trim();
        if (!IpUtils.isValidIpv4(startIp)) {
            throw new InvalidSeriesException("The start address must be a valid IPv4 address.");
        }
        String cluster = variable("cluster", params.cluster());
        String site = variable("site", params.site());

        List<SeriesTemplateDefinition.Group> templateGroups = definition.groups();
        int groupCount = templateGroups.size();
        long start = IpUtils.ipv4ToLong(startIp);
        List<SeriesGroupsRequest.Group> groups = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) {
            SeriesTemplateDefinition.Group group = templateGroups.get(i);
            Integer count = group.count() != null ? group.count() : params.nodes();
            if (count == null) {
                throw new InvalidSeriesException("The number of nodes is required.");
            }
            checkRange("nodes", count, 1, SeriesPlanner.MAX_COUNT);

            Format format = parseFormat(group.format());
            String prefix = resolve(format.prefix(), group.role(), cluster, site);
            String suffix = resolve(format.suffix(), group.role(), cluster, site);
            String label = group.label() != null ? group.label() : group.role();

            if (interleaved) {
                long groupStep = (long) groupCount * step;
                if (groupStep > SeriesPlanner.MAX_STEP) {
                    throw new InvalidSeriesException("Too many groups for this step: "
                            + "groups x step must be at most " + SeriesPlanner.MAX_STEP + ".");
                }
                long groupStart = start + (long) i * step;
                if (groupStart > IPV4_MAX) {
                    throw new InvalidSeriesException("The series goes beyond the IPv4 address space.");
                }
                groups.add(new SeriesGroupsRequest.Group(label, count, (int) groupStep, prefix,
                        group.startCounter(), format.width(), suffix, IpUtils.longToIpv4(groupStart)));
            } else {
                groups.add(new SeriesGroupsRequest.Group(label, count, step, prefix,
                        group.startCounter(), format.width(), suffix, null));
            }
        }
        return new SeriesGroupsRequest(subnetId, startIp, interleaved ? 0 : gap, mode,
                params.description(), params.temporary(), groups);
    }

    // -------------------------------------------------------
    // Formats
    // -------------------------------------------------------

    private static Format parseFormat(String format) {
        if (format == null || format.isBlank()) {
            throw new InvalidSeriesException("A name format is required.");
        }
        Matcher matcher = TOKEN.matcher(format);
        int counterStart = -1;
        int counterEnd = -1;
        int width = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            if (COUNTER.matcher(name).matches()) {
                if (counterStart >= 0) {
                    throw new InvalidSeriesException(
                            "A name format must contain exactly one counter ({n}, {nn}, {nnn}...): " + format);
                }
                counterStart = matcher.start();
                counterEnd = matcher.end();
                width = name.length();
            } else if (!VARIABLES.contains(name)) {
                throw new InvalidSeriesException(
                        "Unknown variable {" + name + "} in name format: " + format);
            }
        }
        if (counterStart < 0) {
            throw new InvalidSeriesException(
                    "A name format must contain exactly one counter ({n}, {nn}, {nnn}...): " + format);
        }
        String prefix = format.substring(0, counterStart);
        String suffix = format.substring(counterEnd);
        if (hasStrayBrace(prefix) || hasStrayBrace(suffix)) {
            throw new InvalidSeriesException("Unbalanced braces in name format: " + format);
        }
        return new Format(prefix, width, suffix);
    }

    private static boolean hasStrayBrace(String part) {
        return TOKEN.matcher(part).replaceAll("").matches(".*[{}].*");
    }

    private static String resolve(String part, String role, String cluster, String site) {
        Matcher matcher = TOKEN.matcher(part);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "role" -> role;
                case "cluster" -> required("cluster", cluster);
                case "site" -> required("site", site);
                default -> throw new InvalidSeriesException(
                        "Unknown variable {" + matcher.group(1) + "}.");
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String required(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new InvalidSeriesException("The variable " + name + " is required by this template.");
        }
        return value;
    }

    /** Valeur d'une variable : vide si absente, sinon lettres, chiffres, '.', '-' et '_' (40 max). */
    private static String variable(String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (!VALUE.matcher(trimmed).matches()) {
            throw new InvalidSeriesException("The " + name + " must be 1 to 40 characters: letters, "
                    + "digits, '.', '-' or '_', starting with a letter or digit.");
        }
        return trimmed;
    }

    private static void checkRange(String name, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new InvalidSeriesException(
                    "The " + name + " must be between " + min + " and " + max + ".");
        }
    }
}
