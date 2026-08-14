package eu.nurkert.neverUp2Late;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped config.yml is what every fresh install starts from, so its
 * contents are a contract rather than documentation.
 */
class DefaultConfigurationTest {

    private static final YamlConfiguration CONFIG =
            YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));

    private Optional<Map<?, ?>> source(String name) {
        List<Map<?, ?>> sources = CONFIG.getMapList("updates.sources");
        return sources.stream().filter(entry -> name.equals(entry.get("name"))).findFirst();
    }

    @Test
    void shipsAWorkingSelfUpdateSource() {
        Map<?, ?> self = source("neverup2late").orElseThrow(
                () -> new AssertionError("The default configuration must keep NeverUp2Late up to date itself"));

        assertEquals("githubRelease", self.get("type"),
                "self-update runs off the GitHub releases, which is where the jar is published");
        assertEquals("plugins", self.get("target"));
        assertEquals(Boolean.TRUE, self.get("enabled"));

        Map<?, ?> options = (Map<?, ?>) self.get("options");
        assertNotNull(options);
        assertEquals("nurkert", options.get("owner"));
        assertEquals("never-up-2-late", options.get("repository"));
        assertEquals("NeverUp2Late", options.get("installedPlugin"),
                "without this the updater cannot read the running version");
    }

    @Test
    void selfUpdateAssetPatternPicksTheStableJarName() {
        Map<?, ?> options = (Map<?, ?>) source("neverup2late").orElseThrow().get("options");
        // YAML unescaping happens before the regex is compiled, so a wrong number
        // of backslashes here silently turns the dot into "any character".
        Pattern pattern = Pattern.compile(String.valueOf(options.get("assetPattern")));

        assertTrue(pattern.matcher("NeverUp2Late.jar").matches(),
                "the release publishes this constant name next to the versioned one");
        assertFalse(pattern.matcher("never-up-2-late-2.4.5.jar").matches(),
                "picking the versioned asset would rename the jar on every update");
        assertFalse(pattern.matcher("NeverUp2LateXjar").matches(), "the dot must be literal");
    }

    @Test
    void selfUpdateHasAMatchingDefaultFilename() {
        assertEquals("NeverUp2Late.jar", CONFIG.getString("filenames.neverup2late"));
        assertEquals("NeverUp2Late.jar", source("neverup2late").orElseThrow().get("filename"));
    }

    @Test
    void keepsTheCautiousDefaultsAndLeavesThemConfigurable() {
        Map<?, ?> paperOptions = (Map<?, ?>) source("paper").orElseThrow().get("options");
        assertEquals(Boolean.FALSE, paperOptions.get("allowGameVersionUpgrade"),
                "a server must never be moved to a new Minecraft version behind the operator's back");

        assertTrue(CONFIG.getBoolean("updates.respectManualRollback"),
                "a restored backup has to stay restored");
        assertTrue(CONFIG.getBoolean("updates.ignoreUnstable"));
        assertEquals(60, CONFIG.getInt("startupDelaySeconds"),
                "the first check stays out of the boot phase");
    }
}
