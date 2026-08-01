package dev.subnetory.web;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;

class HelpWebControllerTest {

    private final HelpWebController controller = new HelpWebController();

    @Test
    void help_setsModelAttributesAndReturnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.help(model);

        assertThat(view).isEqualTo("help");
        assertThat(model.get("activeSection")).isEqualTo("help");
        assertThat(model.get("pageTitle")).isEqualTo("Aide");
    }

    @Test
    void priseEnMain_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.priseEnMain(model);

        assertThat(view).isEqualTo("help/prise-en-main");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void reseau_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reseau(model);

        assertThat(view).isEqualTo("help/reseau");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void adressesIp_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.adressesIp(model);

        assertThat(view).isEqualTo("help/adresses-ip");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void importExport_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.importExport(model);

        assertThat(view).isEqualTo("help/import-export");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void compteSecurite_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.compteSecurite(model);

        assertThat(view).isEqualTo("help/compte-securite");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void api_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.api(model);

        assertThat(view).isEqualTo("help/api");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }
}
