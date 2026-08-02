package dev.subnetory.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.ui.ExtendedModelMap;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessageSource reel (et non mocke), voir {@link AdminHelpWebControllerTest}
 * pour le rationnel.
 */
class HelpWebControllerTest {

    private static final Locale LOCALE = Locale.FRENCH;

    private final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();

    private final HelpWebController controller;

    HelpWebControllerTest() {
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        controller = new HelpWebController(messageSource);
    }

    @Test
    void help_setsModelAttributesAndReturnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.help(model, LOCALE);

        assertThat(view).isEqualTo("help");
        assertThat(model.get("activeSection")).isEqualTo("help");
        assertThat(model.get("pageTitle")).isEqualTo("Aide");
    }

    @Test
    void priseEnMain_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.priseEnMain(model, LOCALE);

        assertThat(view).isEqualTo("help/prise-en-main");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void reseau_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reseau(model, LOCALE);

        assertThat(view).isEqualTo("help/reseau");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void adressesIp_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.adressesIp(model, LOCALE);

        assertThat(view).isEqualTo("help/adresses-ip");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void importExport_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.importExport(model, LOCALE);

        assertThat(view).isEqualTo("help/import-export");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void compteSecurite_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.compteSecurite(model, LOCALE);

        assertThat(view).isEqualTo("help/compte-securite");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }

    @Test
    void api_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.api(model, LOCALE);

        assertThat(view).isEqualTo("help/api");
        assertThat(model.get("activeSection")).isEqualTo("help");
    }
}
