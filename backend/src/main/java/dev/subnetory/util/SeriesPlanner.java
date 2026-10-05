package dev.subnetory.util;

import dev.subnetory.exception.InvalidSeriesException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Planificateur de reservation d'adresses en serie : calcule, sans aucun acces
 * base de donnees, la liste {@code (IP, nom d'hote)} d'une serie a partir d'une
 * IP de depart, d'une quantite et d'un modele de nom a compteur incremente.
 *
 * <p>L'apercu et la creation reelle appellent exactement la meme methode
 * {@link #plan(Spec, Window)} : ce qui est affiche est donc ce qui sera cree.</p>
 *
 * <p>Deux comportements face a une adresse deja utilisee :</p>
 * <ul>
 *   <li>{@link OccupiedMode#BLOCK} (defaut) : la serie occupe des positions fixes
 *       ({@code depart + i * pas}) ; chaque position non libre est signalee et la
 *       serie ne peut pas etre creee ;</li>
 *   <li>{@link OccupiedMode#SKIP} : les adresses occupees ou reservees sont
 *       sautees (listees dans {@link Plan#skipped()}) et le compteur n'est
 *       consomme que par les adresses effectivement retenues.</li>
 * </ul>
 */
public final class SeriesPlanner {

    public static final int MAX_COUNT = 256;
    public static final int MAX_STEP = 256;
    public static final int MAX_COUNTER_WIDTH = 9;
    public static final long MAX_COUNTER = 999_999_999L;
    public static final int MAX_HOSTNAME_LENGTH = 100;

    private static final long IPV4_MAX = 0xFFFFFFFFL;

    private SeriesPlanner() {}

    /** Comportement face a une adresse deja utilisee. */
    public enum OccupiedMode { BLOCK, SKIP }

    /** Etat d'une adresse candidate. */
    public enum Status {
        /** Libre : peut etre reservee. */
        AVAILABLE,
        /** Deja presente dans le sous-reseau. */
        OCCUPIED,
        /** Passerelle du sous-reseau. */
        RESERVED,
        /** Hors de la plage attribuable (hors sous-reseau, adresse reseau ou broadcast). */
        OUT_OF_RANGE
    }

    /**
     * Parametres d'une serie.
     *
     * @param startIp      premiere IP (IPv4 sans prefixe)
     * @param count        nombre d'adresses a reserver
     * @param step         ecart entre deux adresses de la serie (1 = adresses consecutives)
     * @param prefix       debut du nom d'hote ({@code null} accepte)
     * @param startCounter valeur du compteur pour la premiere adresse
     * @param counterWidth nombre minimal de chiffres du compteur (2 donne 01, 02...)
     * @param suffix       fin du nom d'hote ({@code null} accepte)
     * @param mode         comportement face a une adresse deja utilisee
     */
    public record Spec(String startIp,
                       int count,
                       int step,
                       String prefix,
                       long startCounter,
                       int counterWidth,
                       String suffix,
                       OccupiedMode mode) {}

    /**
     * Contexte du sous-reseau, calcule par l'appelant.
     *
     * @param firstUsable premiere adresse attribuable (valeur numerique non signee)
     * @param lastUsable  derniere adresse attribuable
     * @param gateway     passerelle du sous-reseau, ou {@code null}
     * @param occupied    adresses deja presentes en base, en valeur numerique
     */
    public record Window(long firstUsable, long lastUsable, Long gateway, Set<Long> occupied) {}

    /** Une ligne de la serie ({@code hostname} est {@code null} pour une adresse sautee). */
    public record Row(String ip, String hostname, Status status) {}

    /**
     * Resultat du calcul.
     *
     * @param rows      adresses de la serie, dans l'ordre
     * @param skipped   adresses sautees (mode SKIP uniquement)
     * @param shortfall nombre d'adresses manquantes si la plage est epuisee (mode SKIP)
     */
    public record Plan(List<Row> rows, List<Row> skipped, int shortfall) {

        public Plan {
            rows = List.copyOf(rows);
            skipped = List.copyOf(skipped);
        }

        /** Vrai si la serie complete peut etre creee telle quelle. */
        public boolean canCreate() {
            return shortfall == 0
                    && !rows.isEmpty()
                    && rows.stream().allMatch(r -> r.status() == Status.AVAILABLE);
        }
    }

    /**
     * Verifie les parametres de la serie.
     *
     * @throws InvalidSeriesException si un parametre est invalide
     */
    public static void validate(Spec spec) {
        if (spec == null) {
            throw new InvalidSeriesException("The series definition is required.");
        }
        if (!IpUtils.isValidIpv4(spec.startIp())) {
            throw new InvalidSeriesException("The start address must be a valid IPv4 address.");
        }
        if (spec.count() < 1 || spec.count() > MAX_COUNT) {
            throw new InvalidSeriesException("The count must be between 1 and " + MAX_COUNT + ".");
        }
        if (spec.step() < 1 || spec.step() > MAX_STEP) {
            throw new InvalidSeriesException("The step must be between 1 and " + MAX_STEP + ".");
        }
        if (spec.startCounter() < 0 || spec.startCounter() > MAX_COUNTER) {
            throw new InvalidSeriesException("The start counter must be between 0 and " + MAX_COUNTER + ".");
        }
        if (spec.counterWidth() < 1 || spec.counterWidth() > MAX_COUNTER_WIDTH) {
            throw new InvalidSeriesException(
                    "The counter width must be between 1 and " + MAX_COUNTER_WIDTH + ".");
        }
        if (spec.mode() == null) {
            throw new InvalidSeriesException("The behaviour for occupied addresses is required.");
        }
    }

    /**
     * Calcule la serie.
     *
     * @throws InvalidSeriesException si les parametres sont invalides, si la serie depasse
     *                                l'espace IPv4 ou si un nom d'hote depasse
     *                                {@value #MAX_HOSTNAME_LENGTH} caracteres
     */
    public static Plan plan(Spec spec, Window window) {
        validate(spec);
        long start = IpUtils.ipv4ToLong(spec.startIp());
        return spec.mode() == OccupiedMode.SKIP
                ? planSkipping(spec, window, start)
                : planBlocking(spec, window, start);
    }

    private static Plan planBlocking(Spec spec, Window window, long start) {
        long last = start + (long) (spec.count() - 1) * spec.step();
        if (last > IPV4_MAX) {
            throw new InvalidSeriesException("The series goes beyond the IPv4 address space.");
        }
        List<Row> rows = new ArrayList<>(spec.count());
        for (int i = 0; i < spec.count(); i++) {
            long value = start + (long) i * spec.step();
            rows.add(new Row(IpUtils.longToIpv4(value), hostname(spec, i), statusOf(value, window)));
        }
        return new Plan(rows, List.of(), 0);
    }

    private static Plan planSkipping(Spec spec, Window window, long start) {
        List<Row> rows = new ArrayList<>(spec.count());
        List<Row> skipped = new ArrayList<>();
        long value = start;
        while (rows.size() < spec.count() && value <= window.lastUsable()) {
            Status status = statusOf(value, window);
            String ip = IpUtils.longToIpv4(value);
            if (status == Status.AVAILABLE) {
                rows.add(new Row(ip, hostname(spec, rows.size()), status));
            } else if (status == Status.OUT_OF_RANGE) {
                // Depart hors plage attribuable : rien a sauter, on s'arrete.
                skipped.add(new Row(ip, null, status));
                break;
            } else {
                skipped.add(new Row(ip, null, status));
            }
            value += spec.step();
        }
        return new Plan(rows, skipped, spec.count() - rows.size());
    }

    private static Status statusOf(long value, Window window) {
        if (value < window.firstUsable() || value > window.lastUsable()) {
            return Status.OUT_OF_RANGE;
        }
        if (window.occupied().contains(value)) {
            return Status.OCCUPIED;
        }
        if (window.gateway() != null && window.gateway() == value) {
            return Status.RESERVED;
        }
        return Status.AVAILABLE;
    }

    private static String hostname(Spec spec, int index) {
        String prefix = spec.prefix() == null ? "" : spec.prefix();
        String suffix = spec.suffix() == null ? "" : spec.suffix();
        String counter = String.format(Locale.ROOT, "%0" + spec.counterWidth() + "d", spec.startCounter() + index);
        String name = prefix + counter + suffix;
        if (name.length() > MAX_HOSTNAME_LENGTH) {
            throw new InvalidSeriesException(
                    "A generated host name exceeds " + MAX_HOSTNAME_LENGTH + " characters.");
        }
        return name;
    }
}
