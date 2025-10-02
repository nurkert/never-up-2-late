package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists per-plugin update preferences such as the preferred post-update
 * behaviour and whether automatic update checks should be executed.
 */
public class PluginUpdateSettingsRepository {

    private static final String FILE_NAME = "plugin-settings.yml";
    private static final String ROOT_NODE = "plugins";
    private static final String AUTO_UPDATE_NODE = "autoUpdate";
    private static final String BEHAVIOUR_NODE = "behaviour";

    private final File dataFolder;
    private final Logger logger;
    private final File stateFile;

    private FileConfiguration configuration;

    public PluginUpdateSettingsRepository(File dataFolder, Logger logger) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stateFile = new File(dataFolder, FILE_NAME);
        initialise();
    }

    public static PluginUpdateSettingsRepository forPlugin(JavaPlugin plugin) {
        return new PluginUpdateSettingsRepository(plugin.getDataFolder(), plugin.getLogger());
    }

    private void initialise() {
        ensureDataFolderExists();
        ensureStateFileExists();
        configuration = YamlConfiguration.loadConfiguration(stateFile);
        if (configuration.getConfigurationSection(ROOT_NODE) == null) {
            configuration.createSection(ROOT_NODE);
            saveInternal();
        }
    }

    private void ensureDataFolderExists() {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.log(Level.WARNING, "Could not create plugin data folder at {0}", dataFolder.getAbsolutePath());
        }
    }

    private void ensureStateFileExists() {
        if (!stateFile.exists()) {
            try {
                if (!stateFile.createNewFile()) {
                    logger.log(Level.WARNING, "Unable to create {0}", stateFile.getAbsolutePath());
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to create " + FILE_NAME, e);
            }
        }
    }

    public synchronized PluginUpdateSettings getSettings(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return PluginUpdateSettings.defaultSettings();
        }

        ConfigurationSection section = configuration.getConfigurationSection(pathForPlugin(pluginName));
        if (section == null) {
            return PluginUpdateSettings.defaultSettings();
        }

        boolean autoUpdate = section.getBoolean(AUTO_UPDATE_NODE, true);
        String behaviourValue = section.getString(BEHAVIOUR_NODE);
        UpdateBehaviour behaviour = UpdateBehaviour.parse(behaviourValue)
                .orElse(UpdateBehaviour.REQUIRE_RESTART);
        return new PluginUpdateSettings(autoUpdate, behaviour);
    }

    public synchronized void saveSettings(String pluginName, PluginUpdateSettings settings) {
        if (pluginName == null || pluginName.isBlank() || settings == null) {
            return;
        }

        ConfigurationSection section = configuration.getConfigurationSection(pathForPlugin(pluginName));
        if (section == null) {
            section = configuration.createSection(pathForPlugin(pluginName));
        }

        section.set(AUTO_UPDATE_NODE, settings.autoUpdateEnabled());
        section.set(BEHAVIOUR_NODE, settings.behaviour().name());
        saveInternal();
    }

    public synchronized Map<String, PluginUpdateSettings> getAllSettings() {
        ConfigurationSection root = configuration.getConfigurationSection(ROOT_NODE);
        if (root == null) {
            return Collections.emptyMap();
        }

        Map<String, PluginUpdateSettings> settings = new HashMap<>();
        for (String key : root.getKeys(false)) {
            settings.put(key, getSettings(key));
        }
        return settings;
    }

    private String pathForPlugin(String pluginName) {
        return ROOT_NODE + "." + pluginName;
    }

    private void saveInternal() {
        try {
            configuration.save(stateFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save plugin update settings", e);
        }
    }

    public enum UpdateBehaviour {
        AUTO_RELOAD,
        REQUIRE_RESTART;

        private static final Map<String, UpdateBehaviour> LOOKUP;

        static {
            Map<String, UpdateBehaviour> map = new HashMap<>();
            for (UpdateBehaviour behaviour : values()) {
                map.put(behaviour.name(), behaviour);
            }
            LOOKUP = Collections.unmodifiableMap(map);
        }

        public static java.util.Optional<UpdateBehaviour> parse(String value) {
            if (value == null || value.isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.ofNullable(LOOKUP.get(value.trim().toUpperCase()))
                    .or(() -> java.util.Optional.ofNullable(LOOKUP.get(value.trim())));
        }
    }

    public record PluginUpdateSettings(boolean autoUpdateEnabled, UpdateBehaviour behaviour) {
        public PluginUpdateSettings {
            if (behaviour == null) {
                behaviour = UpdateBehaviour.REQUIRE_RESTART;
            }
        }

        public static PluginUpdateSettings defaultSettings() {
            return new PluginUpdateSettings(true, UpdateBehaviour.REQUIRE_RESTART);
        }
    }
}

