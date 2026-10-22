package dev.subnetory.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.dto.SeriesTemplateExpandRequest;
import dev.subnetory.exception.InvalidSeriesException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests unitaires de {@link SeriesTemplateExpander} (calcul pur, sans base de donnees). */
class SeriesTemplateExpanderTest {

    private static final Long SUBNET_ID = 7L;

    // -------------------------------------------------------
    // expand - groupes sequentiels
    // -------------------------------------------------------

    @Test
    @DisplayName("expand sequentiel : un groupe par role, nom construit depuis le cluster et le compteur")
    void expand_sequentialBuildsOneGroupPerRole() {
        SeriesGroupsRequest request = SeriesTemplateExpander.expand(SUBNET_ID, nutanix(),
                params("NTNX01", null, 4, "10.0.0.10", null, null, null));

        assertThat(request.subnetId()).isEqualTo(SUBNET_ID);
        assertThat(request.startIp()).isEqualTo("10.0.0.10");
        assertThat(request.gap()).isZero();
        assertThat(request.occupiedMode()).isEqualTo("block");
        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::label)
                .containsExactly("HOST", "CVM", "ILO");
        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::prefix)
                .containsExactly("NTNX01-HOST-", "NTNX01-CVM-", "NTNX01-ILO-");
        assertThat(request.groups()).allSatisfy(g -> {
            assertThat(g.count()).isEqualTo(4);
            assertThat(g.step()).isEqualTo(1);
            assertThat(g.counterWidth()).isEqualTo(2);
            assertThat(g.startCounter()).isEqualTo(1L);
            assertThat(g.suffix()).isEmpty();
            assertThat(g.startIp()).isNull();
        });
    }

    @Test
    @DisplayName("expand sequentiel : ecart, pas et mode occupe du template, remplaces par les parametres")
    void expand_parametersOverrideTemplateDefaults() {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(
                null, 2, 3, "skip", List.of(group("HOST", "{cluster}-{role}-{nn}", null, null)));

        SeriesGroupsRequest defaults = SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "10.0.0.10", null, null, null));
        assertThat(defaults.gap()).isEqualTo(2);
        assertThat(defaults.occupiedMode()).isEqualTo("skip");
        assertThat(defaults.groups().get(0).step()).isEqualTo(3);

        SeriesGroupsRequest overridden = SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "10.0.0.10", 5, 1, "block"));
        assertThat(overridden.gap()).isEqualTo(5);
        assertThat(overridden.occupiedMode()).isEqualTo("block");
        assertThat(overridden.groups().get(0).step()).isEqualTo(1);
    }

    @Test
    @DisplayName("expand : nombre fixe, compteur de depart, largeur et suffixe viennent du format du groupe")
    void expand_groupCountCounterAndFormat() {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(null, null, null, null, List.of(
                group("VIP", "{site}-vip{nnn}-mgmt", 1, 10L),
                group("HOST", "{cluster}-{role}-{n}", null, null)));

        SeriesGroupsRequest request = SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", "PAR", 6, "10.0.0.10", null, null, null));

        SeriesGroupsRequest.Group vip = request.groups().get(0);
        assertThat(vip.count()).isEqualTo(1);
        assertThat(vip.startCounter()).isEqualTo(10L);
        assertThat(vip.counterWidth()).isEqualTo(3);
        assertThat(vip.prefix()).isEqualTo("PAR-vip");
        assertThat(vip.suffix()).isEqualTo("-mgmt");
        SeriesGroupsRequest.Group host = request.groups().get(1);
        assertThat(host.count()).isEqualTo(6);
        assertThat(host.counterWidth()).isEqualTo(1);
        assertThat(host.prefix()).isEqualTo("C1-HOST-");
    }

    @Test
    @DisplayName("expand : description et caractere temporaire sont transmis")
    void expand_carriesDescriptionAndTemporary() {
        SeriesGroupsRequest request = SeriesTemplateExpander.expand(SUBNET_ID, nutanix(),
                new SeriesTemplateExpander.Params("C1", null, 2, "10.0.0.10", null, null, null,
                        "Cluster prod", true));

        assertThat(request.description()).isEqualTo("Cluster prod");
        assertThat(request.temporary()).isTrue();
    }

    // -------------------------------------------------------
    // expand - mode entrelace
    // -------------------------------------------------------

    @Test
    @DisplayName("expand entrelace : un noeud = hote + CVM + IPMI a la suite, puis le noeud suivant")
    void expand_interleavedStartsEachGroupOnNextAddress() {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(
                SeriesTemplateDefinition.INTERLEAVED, 4, null, null, nutanix().groups());

        SeriesGroupsRequest request = SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 4, "10.0.0.10", null, null, null));

        assertThat(request.gap()).isZero();
        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::startIp)
                .containsExactly("10.0.0.10", "10.0.0.11", "10.0.0.12");
        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::step)
                .containsExactly(3, 3, 3);
    }

    @Test
    @DisplayName("expand entrelace : le pas est multiplie par le nombre de groupes")
    void expand_interleavedHonoursStep() {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(
                SeriesTemplateDefinition.INTERLEAVED, null, 2, null, nutanix().groups());

        SeriesGroupsRequest request = SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "10.0.0.10", null, null, null));

        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::startIp)
                .containsExactly("10.0.0.10", "10.0.0.12", "10.0.0.14");
        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::step)
                .containsExactly(6, 6, 6);
    }

    @Test
    @DisplayName("expand entrelace : ignorer les adresses occupees est refuse")
    void expand_interleavedRejectsSkipMode() {
        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(
                SeriesTemplateDefinition.INTERLEAVED, null, null, null, nutanix().groups());

        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "10.0.0.10", null, null, "skip")))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("Interleaved");
    }

    @Test
    @DisplayName("expand entrelace : groupes x pas > 256 ou sortie de l'espace IPv4 -> refus")
    void expand_interleavedLimits() {
        SeriesTemplateDefinition tooWide = new SeriesTemplateDefinition(
                SeriesTemplateDefinition.INTERLEAVED, null, 30, null, tenGroups());
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, tooWide,
                params("C1", null, 2, "10.0.0.10", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("Too many groups");

        SeriesTemplateDefinition definition = new SeriesTemplateDefinition(
                SeriesTemplateDefinition.INTERLEAVED, null, null, null, nutanix().groups());
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "255.255.255.255", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("IPv4 address space");
    }

    // -------------------------------------------------------
    // expand - parametres invalides
    // -------------------------------------------------------

    @Test
    @DisplayName("expand : parametres invalides refuses")
    void expand_invalidParameters() {
        SeriesTemplateDefinition definition = nutanix();
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition, null))
                .isInstanceOf(InvalidSeriesException.class);
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, null, "10.0.0.10", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("number of nodes");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 300, "10.0.0.10", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("nodes");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "not-an-ip", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("start address");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, null, null, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("start address");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "10.0.0.10", 300, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("gap");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "10.0.0.10", null, 0, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("step");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, definition,
                params("C1", null, 2, "10.0.0.10", null, null, "overwrite")))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("block or skip");
    }

    @Test
    @DisplayName("expand : variables obligatoires et valeurs de variables controlees")
    void expand_variableValues() {
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, nutanix(),
                params(null, null, 2, "10.0.0.10", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("cluster is required");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, nutanix(),
                params("  ", null, 2, "10.0.0.10", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("cluster is required");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, nutanix(),
                params("bad name!", null, 2, "10.0.0.10", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("cluster");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, nutanix(),
                params("x".repeat(41), null, 2, "10.0.0.10", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class);

        SeriesTemplateDefinition withSite = new SeriesTemplateDefinition(null, null, null, null,
                List.of(group("HOST", "{site}-{cluster}-{nn}", null, null)));
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, withSite,
                params("C1", null, 2, "10.0.0.10", null, null, null)))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("site is required");
        assertThat(SeriesTemplateExpander.expand(SUBNET_ID, withSite,
                params(" C1 ", " PAR ", 2, "10.0.0.10", null, null, null))
                .groups().get(0).prefix()).isEqualTo("PAR-C1-");
    }

    // -------------------------------------------------------
    // validate
    // -------------------------------------------------------

    @Test
    @DisplayName("validate : un format de nom doit contenir exactement un compteur")
    void validate_counterRules() {
        assertInvalid(def(group("HOST", "{cluster}-HOST", null, null)), "exactly one counter");
        assertInvalid(def(group("HOST", "{n}-{nn}", null, null)), "exactly one counter");
        assertInvalid(def(group("HOST", "{cluster}-{nnnnnnnnnn}", null, null)), "Unknown variable");
        assertInvalid(def(group("HOST", "{foo}-{nn}", null, null)), "Unknown variable {foo}");
        assertInvalid(def(group("HOST", "{n1}-{nn}", null, null)), "Unbalanced braces");
        assertInvalid(def(group("HOST", "{nn}-}", null, null)), "Unbalanced braces");
        assertInvalid(def(group("HOST", " ", null, null)), "name format is required");
        assertInvalid(def(group("HOST", null, null, null)), "name format is required");
    }

    @Test
    @DisplayName("validate : champs du template et des groupes")
    void validate_fields() {
        SeriesTemplateDefinition.Group ok = group("HOST", "{cluster}-{nn}", null, null);
        assertThatThrownBy(() -> SeriesTemplateExpander.validate(null))
                .isInstanceOf(InvalidSeriesException.class);
        assertInvalid(new SeriesTemplateDefinition("weird", null, null, null, List.of(ok)), "allocation");
        assertInvalid(new SeriesTemplateDefinition(null, 300, null, null, List.of(ok)), "groupGap");
        assertInvalid(new SeriesTemplateDefinition(null, null, 0, null, List.of(ok)), "ipStep");
        assertInvalid(new SeriesTemplateDefinition(null, null, null, "overwrite", List.of(ok)), "onOccupied");
        assertInvalid(new SeriesTemplateDefinition(null, null, null, null, List.of()), "at least one group");
        assertInvalid(new SeriesTemplateDefinition(null, null, null, null, null), "at least one group");
        assertInvalid(new SeriesTemplateDefinition(null, null, null, null, tenGroupsPlusOne()), "at most 10");
        assertInvalid(new SeriesTemplateDefinition(null, null, null, null,
                Collections.singletonList(null)), "must not be empty");
        assertInvalid(def(group("bad role", "{nn}", null, null)), "role");
        assertInvalid(def(group(null, "{nn}", null, null)), "role");
        assertInvalid(def(group("HOST", "{nn}", 300, null)), "count");
        assertInvalid(def(group("HOST", "{nn}", null, 1_000_000_000L)), "start counter");
        assertInvalid(def(new SeriesTemplateDefinition.Group("HOST", "x".repeat(51), "{nn}", null, null)),
                "label");
        SeriesTemplateExpander.validate(def(group("HOST", "{cluster}-{role}-{nnn}", 5, 0L)));
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    // -------------------------------------------------------
    // expand - un sous-reseau par role (per-group)
    // -------------------------------------------------------

    private static SeriesTemplateDefinition perGroup() {
        return new SeriesTemplateDefinition(SeriesTemplateDefinition.PER_GROUP, null, null, null,
                nutanix().groups());
    }

    private static SeriesTemplateExpander.Params withRoles(String startIp,
            SeriesTemplateExpandRequest.RoleTarget... roles) {
        return new SeriesTemplateExpander.Params("NTNX01", null, 4, startIp, null, null, null,
                null, null, Arrays.asList(roles));
    }

    @Test
    @DisplayName("expand per-group : chaque role recoit son sous-reseau et son IP de depart")
    void expand_perGroupAppliesRoleTargets() {
        SeriesGroupsRequest request = SeriesTemplateExpander.expand(SUBNET_ID, perGroup(), withRoles(null,
                new SeriesTemplateExpandRequest.RoleTarget("ILO", 11L, "10.0.10.20"),
                new SeriesTemplateExpandRequest.RoleTarget("host", 12L, "10.0.20.20"),
                new SeriesTemplateExpandRequest.RoleTarget("CVM", 13L, null)));

        assertThat(request.startIp()).isNull();
        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::subnetId)
                .containsExactly(12L, 13L, 11L);
        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::startIp)
                .containsExactly("10.0.20.20", null, "10.0.10.20");
        assertThat(request.groups()).extracting(SeriesGroupsRequest.Group::prefix)
                .containsExactly("NTNX01-HOST-", "NTNX01-CVM-", "NTNX01-ILO-");
    }

    @Test
    @DisplayName("expand per-group : sans cible, les roles suivent le sous-reseau de la demande")
    void expand_perGroupWithoutTargets() {
        SeriesGroupsRequest request = SeriesTemplateExpander.expand(SUBNET_ID, perGroup(),
                withRoles("10.0.0.10"));

        assertThat(request.startIp()).isEqualTo("10.0.0.10");
        assertThat(request.groups()).allSatisfy(g -> {
            assertThat(g.subnetId()).isNull();
            assertThat(g.startIp()).isNull();
        });
    }

    @Test
    @DisplayName("expand : cibles par role refusees hors template per-group, roles inconnus ou en double")
    void expand_roleTargetsAreChecked() {
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, nutanix(), withRoles("10.0.0.10",
                new SeriesTemplateExpandRequest.RoleTarget("HOST", 11L, null))))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("per-group");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, perGroup(), withRoles(null,
                new SeriesTemplateExpandRequest.RoleTarget("NOPE", 11L, null))))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("Unknown role");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, perGroup(), withRoles(null,
                new SeriesTemplateExpandRequest.RoleTarget("HOST", 11L, null),
                new SeriesTemplateExpandRequest.RoleTarget("host", 12L, null))))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, perGroup(), withRoles(null,
                new SeriesTemplateExpandRequest.RoleTarget("HOST", 11L, "not-an-ip"))))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("start address");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, perGroup(), withRoles("bad-ip")))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("start address");
        assertThatThrownBy(() -> SeriesTemplateExpander.expand(SUBNET_ID, perGroup(),
                new SeriesTemplateExpander.Params("NTNX01", null, 4, null, null, null, null, null, null,
                        Arrays.asList((SeriesTemplateExpandRequest.RoleTarget) null))))
                .isInstanceOf(InvalidSeriesException.class).hasMessageContaining("role");
    }

    @Test
    @DisplayName("validate : l'allocation per-group est acceptee")
    void validate_acceptsPerGroup() {
        SeriesTemplateExpander.validate(perGroup());
    }

    private static void assertInvalid(SeriesTemplateDefinition definition, String message) {
        assertThatThrownBy(() -> SeriesTemplateExpander.validate(definition))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining(message);
    }

    private static SeriesTemplateDefinition def(SeriesTemplateDefinition.Group... groups) {
        return new SeriesTemplateDefinition(null, null, null, null, Arrays.asList(groups));
    }

    private static SeriesTemplateDefinition.Group group(String role, String format, Integer count,
                                                        Long startCounter) {
        return new SeriesTemplateDefinition.Group(role, null, format, count, startCounter);
    }

    private static SeriesTemplateDefinition nutanix() {
        return def(
                group("HOST", "{cluster}-{role}-{nn}", null, null),
                group("CVM", "{cluster}-{role}-{nn}", null, null),
                group("ILO", "{cluster}-{role}-{nn}", null, null));
    }

    private static List<SeriesTemplateDefinition.Group> tenGroups() {
        return IntStream.range(0, 10)
                .mapToObj(i -> group("G" + i, "{cluster}-{role}-{nn}", null, null)).toList();
    }

    private static List<SeriesTemplateDefinition.Group> tenGroupsPlusOne() {
        return IntStream.range(0, 11)
                .mapToObj(i -> group("G" + i, "{cluster}-{role}-{nn}", null, null)).toList();
    }

    private static SeriesTemplateExpander.Params params(String cluster, String site, Integer nodes,
                                                        String startIp, Integer gap, Integer step,
                                                        String occupiedMode) {
        return new SeriesTemplateExpander.Params(cluster, site, nodes, startIp, gap, step,
                occupiedMode, null, null);
    }
}
