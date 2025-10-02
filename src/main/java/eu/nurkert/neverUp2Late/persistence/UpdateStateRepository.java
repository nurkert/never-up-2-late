package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repository that manages the persistent update state stored in {@code plugins.yml}.
 *
 * <p>The repository normalizes the configuration into the schema</p>
 *
 * <pre>
 * plugins:
 *   &lt;sourceId&gt;:
 *     build: &lt;int&gt;
 *     version: &lt;string&gt;
 * </pre>
 *
 * <p>Legacy structures are migrated automatically and written back to disk.</p>
 */
public class UpdateStateRepository {

    private static final String ROOT_NODE = "plugins";
    private static final String BUILD_NODE = "build";
    private static final String VERSION_NODE = "version";
    private static final String FILE_NAME = "plugins.yml";

    private final File dataFolder;
    private final Logger logger;
    private final File stateFile;
    private FileConfiguration configuration;

    public UpdateStateRepository(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.stateFile = new File(dataFolder, FILE_NAME);
        initialise();
    }

    public static UpdateStateRepository forPlugin(JavaPlugin plugin) {
        return new UpdateStateRepository(plugin.getDataFolder(), plugin.getLogger());
    }

    private void initialise() {
        ensureDataFolderExists();
        ensureStateFileExists();

        configuration = YamlConfiguration.loadConfiguration(stateFile);

        boolean mutated = migrateLegacyState();
        mutated |= ensurePluginsSectionExists();
        validateSchema();

        if (mutated) {
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

    private boolean ensurePluginsSectionExists() {
        if (configuration.getConfigurationSection(ROOT_NODE) == null) {
            configuration.createSection(ROOT_NODE);
            return true;
        }
        return false;
    }

    private boolean migrateLegacyState() {
        boolean mutated = false;

        List<String> topLevelKeys = new ArrayList<>(configuration.getKeys(false));
        for (String key : topLevelKeys) {
            if (ROOT_NODE.equals(key)) {
                continue;
            }
            Object value = configuration.get(key);
            if (value instanceof Number number) {
                configuration.set(pathForBuild(key), number.intValue());
                mutated = true;
            } else if (value instanceof String string) {
                configuration.set(pathForVersion(key), string);
                mutated = true;
            } else if (value instanceof ConfigurationSection section) {
                copySection(section, configuration.createSection(ROOT_NODE + "." + key));
                mutated = true;
            } else if (value != null) {
                logger.log(Level.WARNING, "Removing unsupported legacy entry {0} from update state", key);
                mutated = true;
            }
            configuration.set(key, null);
        }

        ConfigurationSection pluginsSection = configuration.getConfigurationSection(ROOT_NODE);
        if (pluginsSection == null) {
            return mutated;
        }

        List<String> pluginKeys = new ArrayList<>(pluginsSection.getKeys(false));
        for (String pluginKey : pluginKeys) {
            Object value = pluginsSection.get(pluginKey);
            if (value instanceof ConfigurationSection section) {
                mutated |= normalisePluginSection(section, pluginKey);
                continue;
            }
            if (value instanceof Number number) {
                pluginsSection.set(pluginKey, null);
                pluginsSection.set(pluginKey + "." + BUILD_NODE, number.intValue());
                mutated = true;
                continue;
            }
            if (value instanceof String string) {
                pluginsSection.set(pluginKey, null);
                pluginsSection.set(pluginKey + "." + VERSION_NODE, string);
                mutated = true;
                continue;
            }
            if (value == null) {
                pluginsSection.set(pluginKey, null);
                mutated = true;
                continue;
            }
            logger.log(Level.WARNING, "Removing unsupported value for plugin entry {0}", pluginKey);
            pluginsSection.set(pluginKey, null);
            mutated = true;
        }

        return mutated;
    }

    private boolean normalisePluginSection(ConfigurationSection section, String pluginKey) {
        boolean mutated = false;
        List<String> keys = new ArrayList<>(section.getKeys(false));
        for (String childKey : keys) {
            if (BUILD_NODE.equals(childKey)) {
                Object value = section.get(childKey);
                if (!(value instanceof Number)) {
                    int build = section.getInt(childKey);
                    section.set(childKey, build);
                    mutated = true;
                }
            } else if (VERSION_NODE.equals(childKey)) {
                Object value = section.get(childKey);
                if (value != null && !(value instanceof String)) {
                    section.set(childKey, value.toString());
                    mutated = true;
                }
            } else {
                logger.log(Level.WARNING,
                        "Removing unknown field {0} for plugin entry {1}", new Object[]{childKey, pluginKey});
                section.set(childKey, null);
                mutated = true;
            }
        }
        return mutated;
    }

    private void validateSchema() {
        ConfigurationSection pluginsSection = configuration.getConfigurationSection(ROOT_NODE);
        if (pluginsSection == null) {
            throw new IllegalStateException("Missing '" + ROOT_NODE + "' section in update state file");
        }
        for (String pluginKey : pluginsSection.getKeys(false)) {
            ConfigurationSection section = pluginsSection.getConfigurationSection(pluginKey);
            if (section == null) {
                throw new IllegalStateException("Plugin entry '" + pluginKey + "' must be a configuration section");
            }
            for (String childKey : section.getKeys(false)) {
                if (!BUILD_NODE.equals(childKey) && !VERSION_NODE.equals(childKey)) {
                    throw new IllegalStateException(
                            "Unknown field '" + childKey + "' for plugin entry '" + pluginKey + "'");
                }
            }
        }
    }

    public Optional<PluginState> find(String pluginName) {
        ConfigurationSection section = configuration.getConfigurationSection(pathForPlugin(pluginName));
        if (section == null) {
            return Optional.empty();
        }
        boolean hasBuild = section.contains(BUILD_NODE);
        boolean hasVersion = section.contains(VERSION_NODE);
        if (!hasBuild && !hasVersion) {
            return Optional.empty();
        }
        int build = hasBuild ? section.getInt(BUILD_NODE) : -1;
        String version = hasVersion ? section.getString(VERSION_NODE) : null;
        return Optional.of(new PluginState(build, version));
    }

    public boolean hasPluginInfo(String pluginName) {
        return find(pluginName).isPresent();
    }

    public int getStoredBuild(String pluginName) {
        return find(pluginName).map(PluginState::build).orElse(-1);
    }

    public String getStoredVersion(String pluginName) {
        return find(pluginName).map(PluginState::version).orElse(null);
    }

    public void saveLatestBuild(String pluginName, int build, String version) {
        savePluginState(pluginName, build, version);
    }

    public void savePluginState(String pluginName, Integer build, String version) {
        if (pluginName == null || pluginName.isBlank()) {
            return;
        }

        if (build != null) {
            configuration.set(pathForBuild(pluginName), build);
        } else {
            configuration.set(pathForBuild(pluginName), null);
        }

        if (version != null) {
            configuration.set(pathForVersion(pluginName), version);
        } else {
            configuration.set(pathForVersion(pluginName), null);
        }

        saveInternal();
    }

    private String pathForPlugin(String pluginName) {
        return ROOT_NODE + "." + pluginName;
    }

    private String pathForBuild(String pluginName) {
        return pathForPlugin(pluginName) + "." + BUILD_NODE;
    }

    private String pathForVersion(String pluginName) {
        return pathForPlugin(pluginName) + "." + VERSION_NODE;
    }

    private void saveInternal() {
        try {
            configuration.save(stateFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save update state", e);
        }
    }

    private void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection nestedSource) {
                copySection(nestedSource, target.createSection(key));
            } else {
                target.set(key, value);
            }
        }
    }

    public record PluginState(int build, String version) {
    }
}
