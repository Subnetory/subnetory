package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesTemplateDefinition;
import dev.subnetory.util.SeriesTemplateExpander;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Les presets livres avec l'application doivent tous etre valides et utilisables. */
class BuiltinSeriesTemplatesTest {

    private final BuiltinSeriesTemplates builtins = new BuiltinSeriesTemplates(JsonMapper.builder().build());

    @Test
    @DisplayName("les presets Nutanix, serveurs, Proxmox et VMware sont livres")
    void presetsAreShipped() {
        assertThat(builtins.all()).extracting(BuiltinSeriesTemplates.Builtin::key).contains(
                "nutanix-hpe", "nutanix-dell", "nutanix-lenovo", "nutanix-supermicro",
                "server-hpe", "server-dell", "server-lenovo", "server-supermicro",
                "proxmox-cluster", "vmware-esxi", "nutanix-per-network");
        assertThat(builtins.all()).extracting(BuiltinSeriesTemplates.Builtin::key).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("chaque preset est valide et s'applique a un cluster de 4 noeuds")
    void everyPresetExpands() {
        for (BuiltinSeriesTemplates.Builtin preset : builtins.all()) {
            assertThat(preset.name()).as(preset.key()).isNotBlank();
            SeriesTemplateExpander.validate(preset.definition());

            SeriesGroupsRequest request = SeriesTemplateExpander.expand(1L, preset.definition(),
                    new SeriesTemplateExpander.Params("CL01", "PAR", 4, "10.0.0.10", null, null, null,
                            null, null));
            assertThat(request.groups()).as(preset.key()).hasSameSizeAs(preset.definition().groups());
            assertThat(request.groups()).allSatisfy(g -> {
                assertThat(g.count()).isEqualTo(4);
                assertThat(g.prefix()).startsWith("CL01-");
            });
        }
    }

    @Test
    @DisplayName("preset Nutanix HPE : hotes, CVM puis iLO, en groupes sequentiels")
    void nutanixHpeLayout() {
        SeriesTemplateDefinition definition = builtins.find("nutanix-hpe").orElseThrow().definition();

        assertThat(definition.allocation()).isEqualTo(SeriesTemplateDefinition.SEQUENTIAL);
        List<String> roles = definition.groups().stream().map(SeriesTemplateDefinition.Group::role).toList();
        assertThat(roles).containsExactly("HOST", "CVM", "ILO");
    }

    @Test
    @DisplayName("preset Nutanix un sous-reseau par role : IPMI, hotes puis CVM, en mode per-group")
    void nutanixPerNetworkLayout() {
        SeriesTemplateDefinition definition = builtins.find("nutanix-per-network").orElseThrow().definition();

        assertThat(definition.allocation()).isEqualTo(SeriesTemplateDefinition.PER_GROUP);
        List<String> roles = definition.groups().stream().map(SeriesTemplateDefinition.Group::role).toList();
        assertThat(roles).containsExactly("IPMI", "HOST", "CVM");
    }

    @Test
    @DisplayName("find : cle inconnue -> vide")
    void findUnknownKey() {
        assertThat(builtins.find("nope")).isEmpty();
    }
}
