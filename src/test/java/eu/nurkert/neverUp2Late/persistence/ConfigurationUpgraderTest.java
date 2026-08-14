package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade runs against files this plugin did not write and cannot see
 * beforehand, so the promises it makes have to hold without exception: add what
 * is missing, never touch what is there, and never do the same thing twice.
 */
class ConfigurationUpgraderTest {

    private final Logger logger = Logger.getLogger("test");

    private YamlConfiguration olderInstallation() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("updateInterval", 180);
        configuration.set("filenames.paper", "paper.jar");
        configuration.set("updates.ignoreUnstable", true);
        configuration.set("updates.sources", List.of(Map.of(
                "name", "paper", "type", "paper", "target", "server",
                "filename", "paper.jar", "enabled", true)));
        return configuration;
    }

    private boolean hasSource(YamlConfiguration configuration, String name) {
        return configuration.getMapList("updates.sources").stream()
                .anyMatch(entry -> name.equalsIgnoreCase(Objects.toString(entry.get("name"), "")));
    }

    @Test
    void addsTheSettingsAnOlderFileNeverHad() {
        YamlConfiguration configuration = olderInstallation();

        assertTrue(new ConfigurationUpgrader(configuration, logger).upgrade());

        assertEquals(60, configuration.getInt("startupDelaySeconds"));
        assertTrue(configuration.getBoolean("updates.respectManualRollback"));
        assertEquals("NeverUp2Late.jar", configuration.getString("filenames.neverup2late"));
        assertTrue(hasSource(configuration, "neverup2late"),
                "an older install could never update the plugin itself");
    }

    @Test
    void neverChangesAValueTheOperatorAlreadySet() {
        YamlConfiguration configuration = olderInstallation();
        configuration.set("startupDelaySeconds", 5);
        configuration.set("updates.respectManualRollback", false);
        configuration.set("filenames.neverup2late", "MyOwnName.jar");

        new ConfigurationUpgrader(configuration, logger).upgrade();

        assertEquals(5, configuration.getInt("startupDelaySeconds"));
        assertFalse(configuration.getBoolean("updates.respectManualRollback"));
        assertEquals("MyOwnName.jar", configuration.getString("filenames.neverup2late"));
    }

    @Test
    void leavesAnExistingSelfUpdateSourceAlone() {
        YamlConfiguration configuration = olderInstallation();
        configuration.set("updates.sources", List.of(
                Map.of("name", "paper", "type", "paper", "target", "server", "enabled", true),
                Map.of("name", "NeverUp2Late", "type", "spigot", "target", "plugins", "enabled", false)));

        new ConfigurationUpgrader(configuration, logger).upgrade();

        List<Map<?, ?>> sources = configuration.getMapList("updates.sources");
        assertEquals(2, sources.size(), "the operator's own entry must not be duplicated");
        assertEquals("spigot", sources.get(1).get("type"), "nor replaced");
        assertEquals(false, sources.get(1).get("enabled"), "nor re-enabled");
    }

    @Test
    void doesNotBringBackSomethingDeletedOnPurpose() {
        YamlConfiguration configuration = olderInstallation();
        new ConfigurationUpgrader(configuration, logger).upgrade();
        assertTrue(hasSource(configuration, "neverup2late"));

        // The operator decides they would rather update the plugin by hand.
        configuration.set("updates.sources", configuration.getMapList("updates.sources").stream()
                .filter(entry -> !"neverup2late".equalsIgnoreCase(Objects.toString(entry.get("name"), "")))
                .toList());

        assertFalse(new ConfigurationUpgrader(configuration, logger).upgrade(),
                "a completed upgrade must not run a second time");
        assertFalse(hasSource(configuration, "neverup2late"),
                "and must not resurrect what was removed on purpose");
    }

    @Test
    void doesNothingToAFreshlyShippedConfiguration() {
        YamlConfiguration shipped =
                YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));

        assertFalse(new ConfigurationUpgrader(shipped, logger).upgrade(),
                "the packaged file is already current");
    }

    @Test
    void stillUpgradesWhenThePackagedFileIsAttachedAsDefaults() {
        // This is the shape at runtime: JavaPlugin#getConfig() hands out the
        // operator's file with the packaged config.yml behind it. A lookup that
        // reads through to those defaults would see configVersion 1 and skip the
        // upgrade the old file actually needs.
        YamlConfiguration packaged =
                YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        YamlConfiguration configuration = olderInstallation();
        configuration.setDefaults(packaged);

        assertFalse(configuration.isSet("configVersion"), "the operator's own file has no version");
        assertTrue(new ConfigurationUpgrader(configuration, logger).upgrade(),
                "an older file must still be upgraded behind the packaged defaults");
        assertTrue(hasSource(configuration, "neverup2late"));
        assertFalse(configuration.options().copyDefaults(),
                "saving must not adopt packaged defaults the operator never chose");
    }

    @Test
    void leavesADeliberatelyEmptiedSourceListEmpty() {
        YamlConfiguration configuration = olderInstallation();
        configuration.set("updates.sources", List.of());

        new ConfigurationUpgrader(configuration, logger).upgrade();

        assertTrue(configuration.getMapList("updates.sources").isEmpty(),
                "an empty list means 'manage nothing' - putting a source back would restart updating");
    }

    @Test
    void doesNotTouchASourceListHoldingSomethingUnexpected() {
        YamlConfiguration configuration = olderInstallation();
        configuration.set("updates.sources", List.of("paper", "geyser"));

        new ConfigurationUpgrader(configuration, logger).upgrade();

        assertEquals(List.of("paper", "geyser"), configuration.getList("updates.sources"),
                "rewriting the list would silently delete what getMapList cannot read");
    }

    @Test
    void doesNotReviveASourceRemovedFromARecentConfiguration() {
        // This file already knows settings that shipped with the self-update
        // source, so its absence is a decision rather than an omission.
        YamlConfiguration configuration = olderInstallation();
        configuration.set("startupDelaySeconds", 60);
        configuration.set("updates.respectManualRollback", true);

        new ConfigurationUpgrader(configuration, logger).upgrade();

        assertFalse(hasSource(configuration, "neverup2late"));
    }

    @Test
    void doesNotClaimTheSourceExistsJustBecauseTheDefaultsHaveIt() {
        YamlConfiguration packaged =
                YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("updateInterval", 180);
        configuration.setDefaults(packaged);

        new ConfigurationUpgrader(configuration, logger).upgrade();

        assertFalse(configuration.isSet("updates.sources"),
                "a file without its own source list keeps using the packaged ones");
    }

    @Test
    void alsoUnderstandsTheSectionLayout() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("updates.sources.paper.type", "paper");
        configuration.set("updates.sources.paper.target", "server");

        new ConfigurationUpgrader(configuration, logger).upgrade();

        assertEquals("githubRelease", configuration.getString("updates.sources.neverup2late.type"));
        assertEquals("nurkert", configuration.getString("updates.sources.neverup2late.options.owner"));
        assertEquals("paper", configuration.getString("updates.sources.paper.type"), "untouched");
    }
}
