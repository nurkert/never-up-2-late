package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LegacyConfigMigratorTest {

    private final Logger logger = Logger.getLogger("test");

    @Test
    void migratesLegacyPluginStateAndSettings(@TempDir Path tempDir) {
        UpdateStateRepository stateRepository = new UpdateStateRepository(tempDir.toFile(), logger);
        PluginUpdateSettingsRepository settingsRepository = new PluginUpdateSettingsRepository(tempDir.toFile(), logger);

        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection pluginsSection = configuration.createSection("plugins");
        ConfigurationSection paperSection = pluginsSection.createSection("paper");
        paperSection.set("build", 42);
        paperSection.set("version", "1.2.3");
        paperSection.set("autoUpdate", false);
        paperSection.set("behaviour", "AUTO_RELOAD");
        paperSection.set("retainUpstreamFilename", true);

        LegacyConfigMigrator migrator = new LegacyConfigMigrator(configuration, stateRepository, settingsRepository, logger);

        assertTrue(migrator.migrate(), "Expected migration to mutate configuration");
        assertFalse(configuration.contains("plugins"), "Legacy plugins section should be removed");

        Optional<UpdateStateRepository.PluginState> state = stateRepository.find("paper");
        assertTrue(state.isPresent());
        assertEquals(42, state.get().build());
        assertEquals("1.2.3", state.get().version());

        PluginUpdateSettingsRepository.PluginUpdateSettings settings = settingsRepository.getSettings("paper");
        assertFalse(settings.autoUpdateEnabled());
        assertEquals(PluginUpdateSettingsRepository.UpdateBehaviour.AUTO_RELOAD, settings.behaviour());
        assertTrue(settings.retainUpstreamFilename());
    }

    @Test
    void ignoresUnsupportedLegacyValues(@TempDir Path tempDir) {
        UpdateStateRepository stateRepository = new UpdateStateRepository(tempDir.toFile(), logger);
        PluginUpdateSettingsRepository settingsRepository = new PluginUpdateSettingsRepository(tempDir.toFile(), logger);

        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection pluginsSection = configuration.createSection("plugins");
        pluginsSection.set("geyser", 77);
        pluginsSection.set("invalid", new Object());

        LegacyConfigMigrator migrator = new LegacyConfigMigrator(configuration, stateRepository, settingsRepository, logger);

        assertTrue(migrator.migrate());
        assertFalse(configuration.contains("plugins"));

        Optional<UpdateStateRepository.PluginState> state = stateRepository.find("geyser");
        assertTrue(state.isPresent());
        assertEquals(77, state.get().build());

        PluginUpdateSettingsRepository.PluginUpdateSettings settings = settingsRepository.getSettings("geyser");
        assertEquals(PluginUpdateSettingsRepository.PluginUpdateSettings.defaultSettings(), settings);
    }
}
