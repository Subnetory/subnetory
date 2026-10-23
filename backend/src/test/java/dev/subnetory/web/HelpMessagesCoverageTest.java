package dev.subnetory.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou de l'aide en ligne : toute cle {@code #{...}} utilisee par une page
 * d'aide (utilisateur ou administrateur) doit exister dans les trois bundles
 * (defaut, francais, anglais), pour qu'aucune section ne s'affiche avec une
 * cle brute ou sans traduction.
 */
class HelpMessagesCoverageTest {

    private static final Pattern KEY = Pattern.compile("#\\{([A-Za-z0-9_.\\-]+)[}(]");

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test
    void everyKeyUsedByHelpPagesExistsInAllBundles() throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        List<Resource> pages = new ArrayList<>();
        pages.add(resolver.getResource("classpath:templates/help.html"));
        pages.add(resolver.getResource("classpath:templates/admin/help.html"));
        for (Resource r : resolver.getResources("classpath:templates/help/*.html")) {
            pages.add(r);
        }
        for (Resource r : resolver.getResources("classpath:templates/admin/help/*.html")) {
            pages.add(r);
        }
        for (Resource page : pages) {
            try (InputStream in = page.getInputStream()) {
                Matcher m = KEY.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        assertThat(keys).as("cles d'aide detectees").isNotEmpty();

        for (String bundle : List.of("messages.properties", "messages_fr.properties", "messages_en.properties")) {
            Properties props = load(bundle);
            List<String> missing = new ArrayList<>();
            for (String key : keys) {
                String value = props.getProperty(key);
                if (value == null || value.isBlank()) {
                    missing.add(key);
                }
            }
            assertThat(missing).as("cles d'aide absentes de " + bundle).isEmpty();
        }
    }

    private Properties load(String name) throws IOException {
        Properties props = new Properties();
        try (InputStream in = resolver.getResource("classpath:" + name).getInputStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
