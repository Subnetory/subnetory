package dev.subnetory.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.util.SeriesPlanner.OccupiedMode;
import dev.subnetory.util.SeriesPlanner.Plan;
import dev.subnetory.util.SeriesPlanner.Row;
import dev.subnetory.util.SeriesPlanner.Spec;
import dev.subnetory.util.SeriesPlanner.Status;
import dev.subnetory.util.SeriesPlanner.Window;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests unitaires du calcul pur des series (aucune base de donnees). */
class SeriesPlannerTest {

    /** Fenetre d'un /24 10.10.10.0 : attribuables .1 a .254, sans passerelle ni adresse occupee. */
    private static Window freeWindow() {
        return window(null);
    }

    private static Window window(String gateway, String... occupiedIps) {
        Set<Long> occupied = new HashSet<>();
        for (String ip : occupiedIps) {
            occupied.add(IpUtils.ipv4ToLong(ip));
        }
        return new Window(
                IpUtils.ipv4ToLong("10.10.10.1"),
                IpUtils.ipv4ToLong("10.10.10.254"),
                gateway == null ? null : IpUtils.ipv4ToLong(gateway),
                occupied);
    }

    private static Spec spec(String startIp, int count, OccupiedMode mode) {
        return new Spec(startIp, count, 1, "NTNX-HOST-", 1, 2, "", mode);
    }

    private static List<String> ips(List<Row> rows) {
        return rows.stream().map(Row::ip).toList();
    }

    private static List<String> names(List<Row> rows) {
        return rows.stream().map(Row::hostname).toList();
    }

    // -------------------------------------------------------
    // Mode BLOCK
    // -------------------------------------------------------

    @Test
    @DisplayName("BLOCK : 3 adresses consecutives, noms NTNX-HOST-01 a 03")
    void block_consecutiveSeries() {
        Plan plan = SeriesPlanner.plan(spec("10.10.10.21", 3, OccupiedMode.BLOCK), freeWindow());

        assertThat(ips(plan.rows())).containsExactly("10.10.10.21", "10.10.10.22", "10.10.10.23");
        assertThat(names(plan.rows())).containsExactly("NTNX-HOST-01", "NTNX-HOST-02", "NTNX-HOST-03");
        assertThat(plan.rows()).allMatch(r -> r.status() == Status.AVAILABLE);
        assertThat(plan.skipped()).isEmpty();
        assertThat(plan.shortfall()).isZero();
        assertThat(plan.canCreate()).isTrue();
    }

    @Test
    @DisplayName("BLOCK : pas de 2, compteur sur 3 chiffres, prefixe et suffixe")
    void block_stepWidthPrefixAndSuffix() {
        Spec spec = new Spec("10.10.10.10", 3, 2, "SITE1-", 7, 3, "-ipmi", OccupiedMode.BLOCK);

        Plan plan = SeriesPlanner.plan(spec, freeWindow());

        assertThat(ips(plan.rows())).containsExactly("10.10.10.10", "10.10.10.12", "10.10.10.14");
        assertThat(names(plan.rows()))
                .containsExactly("SITE1-007-ipmi", "SITE1-008-ipmi", "SITE1-009-ipmi");
    }

    @Test
    @DisplayName("BLOCK : prefixe et suffixe null sont acceptes (nom = compteur seul)")
    void block_nullPrefixAndSuffix() {
        Spec spec = new Spec("10.10.10.10", 2, 1, null, 1, 2, null, OccupiedMode.BLOCK);

        Plan plan = SeriesPlanner.plan(spec, freeWindow());

        assertThat(names(plan.rows())).containsExactly("01", "02");
    }

    @Test
    @DisplayName("BLOCK : le compteur depasse sa largeur sans etre tronque")
    void block_counterExceedsWidth() {
        Spec spec = new Spec("10.10.10.10", 3, 1, "n", 98, 2, "", OccupiedMode.BLOCK);

        Plan plan = SeriesPlanner.plan(spec, freeWindow());

        assertThat(names(plan.rows())).containsExactly("n98", "n99", "n100");
    }

    @Test
    @DisplayName("BLOCK : une adresse occupee est signalee et la serie n'est pas creable")
    void block_occupiedAddressBlocksSeries() {
        Plan plan = SeriesPlanner.plan(spec("10.10.10.21", 3, OccupiedMode.BLOCK),
                window(null, "10.10.10.22"));

        assertThat(plan.rows()).extracting(Row::status)
                .containsExactly(Status.AVAILABLE, Status.OCCUPIED, Status.AVAILABLE);
        assertThat(plan.canCreate()).isFalse();
    }

    @Test
    @DisplayName("BLOCK : la passerelle est signalee RESERVED")
    void block_gatewayIsReserved() {
        Plan plan = SeriesPlanner.plan(spec("10.10.10.1", 2, OccupiedMode.BLOCK),
                window("10.10.10.1"));

        assertThat(plan.rows()).extracting(Row::status)
                .containsExactly(Status.RESERVED, Status.AVAILABLE);
        assertThat(plan.canCreate()).isFalse();
    }

    @Test
    @DisplayName("BLOCK : adresse reseau, broadcast et hors sous-reseau sont OUT_OF_RANGE")
    void block_outOfRange() {
        Plan before = SeriesPlanner.plan(spec("10.10.10.0", 1, OccupiedMode.BLOCK), freeWindow());
        Plan after = SeriesPlanner.plan(spec("10.10.10.254", 3, OccupiedMode.BLOCK), freeWindow());

        assertThat(before.rows()).extracting(Row::status).containsExactly(Status.OUT_OF_RANGE);
        assertThat(after.rows()).extracting(Row::status)
                .containsExactly(Status.AVAILABLE, Status.OUT_OF_RANGE, Status.OUT_OF_RANGE);
        assertThat(before.canCreate()).isFalse();
        assertThat(after.canCreate()).isFalse();
    }

    @Test
    @DisplayName("BLOCK : une serie qui depasse l'espace IPv4 est refusee")
    void block_beyondIpv4Space() {
        Spec spec = spec("255.255.255.250", 10, OccupiedMode.BLOCK);

        assertThatThrownBy(() -> SeriesPlanner.plan(spec, freeWindow()))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("IPv4 address space");
    }

    // -------------------------------------------------------
    // Mode SKIP
    // -------------------------------------------------------

    @Test
    @DisplayName("SKIP : les adresses occupees sont sautees, le compteur reste consecutif")
    void skip_skipsOccupiedAndKeepsCounterConsecutive() {
        Plan plan = SeriesPlanner.plan(spec("10.10.10.21", 3, OccupiedMode.SKIP),
                window(null, "10.10.10.22"));

        assertThat(ips(plan.rows())).containsExactly("10.10.10.21", "10.10.10.23", "10.10.10.24");
        assertThat(names(plan.rows())).containsExactly("NTNX-HOST-01", "NTNX-HOST-02", "NTNX-HOST-03");
        assertThat(ips(plan.skipped())).containsExactly("10.10.10.22");
        assertThat(plan.skipped().get(0).status()).isEqualTo(Status.OCCUPIED);
        assertThat(plan.canCreate()).isTrue();
    }

    @Test
    @DisplayName("SKIP : la passerelle est sautee")
    void skip_skipsGateway() {
        Plan plan = SeriesPlanner.plan(spec("10.10.10.1", 2, OccupiedMode.SKIP),
                window("10.10.10.1"));

        assertThat(ips(plan.rows())).containsExactly("10.10.10.2", "10.10.10.3");
        assertThat(plan.skipped()).extracting(Row::status).containsExactly(Status.RESERVED);
    }

    @Test
    @DisplayName("SKIP : plage epuisee -> manque signale, serie non creable")
    void skip_rangeExhausted() {
        Plan plan = SeriesPlanner.plan(spec("10.10.10.253", 4, OccupiedMode.SKIP), freeWindow());

        assertThat(ips(plan.rows())).containsExactly("10.10.10.253", "10.10.10.254");
        assertThat(plan.shortfall()).isEqualTo(2);
        assertThat(plan.canCreate()).isFalse();
    }

    @Test
    @DisplayName("SKIP : depart hors plage attribuable -> rien n'est retenu")
    void skip_startOutsideUsableRange() {
        Plan plan = SeriesPlanner.plan(spec("10.10.10.0", 2, OccupiedMode.SKIP), freeWindow());

        assertThat(plan.rows()).isEmpty();
        assertThat(plan.skipped()).extracting(Row::status).containsExactly(Status.OUT_OF_RANGE);
        assertThat(plan.shortfall()).isEqualTo(2);
        assertThat(plan.canCreate()).isFalse();
    }

    // -------------------------------------------------------
    // Validation
    // -------------------------------------------------------

    @Test
    @DisplayName("Validation : parametres invalides refuses")
    void validation_rejectsInvalidParameters() {
        assertInvalid(new Spec("10.10.10", 3, 1, "a", 1, 2, "", OccupiedMode.BLOCK), "IPv4");
        assertInvalid(new Spec(null, 3, 1, "a", 1, 2, "", OccupiedMode.BLOCK), "IPv4");
        assertInvalid(new Spec("10.10.10.1", 0, 1, "a", 1, 2, "", OccupiedMode.BLOCK), "count");
        assertInvalid(new Spec("10.10.10.1", 257, 1, "a", 1, 2, "", OccupiedMode.BLOCK), "count");
        assertInvalid(new Spec("10.10.10.1", 3, 0, "a", 1, 2, "", OccupiedMode.BLOCK), "step");
        assertInvalid(new Spec("10.10.10.1", 3, 257, "a", 1, 2, "", OccupiedMode.BLOCK), "step");
        assertInvalid(new Spec("10.10.10.1", 3, 1, "a", -1, 2, "", OccupiedMode.BLOCK), "start counter");
        assertInvalid(new Spec("10.10.10.1", 3, 1, "a", 1_000_000_000L, 2, "", OccupiedMode.BLOCK),
                "start counter");
        assertInvalid(new Spec("10.10.10.1", 3, 1, "a", 1, 0, "", OccupiedMode.BLOCK), "counter width");
        assertInvalid(new Spec("10.10.10.1", 3, 1, "a", 1, 10, "", OccupiedMode.BLOCK), "counter width");
        assertInvalid(new Spec("10.10.10.1", 3, 1, "a", 1, 2, "", null), "occupied");
        assertThatThrownBy(() -> SeriesPlanner.validate(null))
                .isInstanceOf(InvalidSeriesException.class);
    }

    @Test
    @DisplayName("Validation : un nom d'hote de plus de 100 caracteres est refuse")
    void validation_hostnameTooLong() {
        Spec spec = new Spec("10.10.10.10", 2, 1, "x".repeat(99), 1, 2, "", OccupiedMode.BLOCK);

        assertThatThrownBy(() -> SeriesPlanner.plan(spec, freeWindow()))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("Plan : sans aucune ligne, la serie n'est pas creable")
    void plan_emptyRowsCannotBeCreated() {
        assertThat(new Plan(List.of(), List.of(), 0).canCreate()).isFalse();
    }

    private static void assertInvalid(Spec spec, String messagePart) {
        assertThatThrownBy(() -> SeriesPlanner.plan(spec, freeWindow()))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining(messagePart);
    }
}
