package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import eu.nurkert.neverUp2Late.util.YamlFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    /** What the source offered the last time we looked, and how that look went. */
    private static final String LATEST_VERSION_NODE = "latestVersion";
    private static final String LATEST_BUILD_NODE = "latestBuild";
    private static final String CHECKED_AT_NODE = "checkedAt";
    private static final String RESULT_NODE = "result";
    private static final String ERROR_NODE = "error";
    private static final Set<String> KNOWN_FIELDS = Set.of(
            BUILD_NODE, VERSION_NODE, LATEST_VERSION_NODE, LATEST_BUILD_NODE,
            CHECKED_AT_NODE, RESULT_NODE, ERROR_NODE);
    private static final String FILE_NAME = "plugins.yml";

    private final File dataFolder;
    private final Logger logger;
    private final File stateFile;
    /**
     * Guarded by the instance lock: the async update run records new builds
     * while the GUI and commands read the very same FileConfiguration on the
     * server thread, and YamlConfiguration is not thread-safe.
     */
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

        configuration = YamlFiles.loadOrQuarantine(stateFile, logger);

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
                entrySection(key, true).set(BUILD_NODE, number.intValue());
                mutated = true;
            } else if (value instanceof String string) {
                entrySection(key, true).set(VERSION_NODE, string);
                mutated = true;
            } else if (value instanceof ConfigurationSection section) {
                copySection(section, pluginsSection(true).createSection(key));
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
            } else if (!KNOWN_FIELDS.contains(childKey)) {
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
                if (!KNOWN_FIELDS.contains(childKey)) {
                    throw new IllegalStateException(
                            "Unknown field '" + childKey + "' for plugin entry '" + pluginKey + "'");
                }
            }
        }
    }

    /**
     * Records what a check found, so the GUI and the status command can answer
     * "is there something new" without going to the network. The pipeline
     * already computes all of this on every cycle and used to discard it.
     */
    public synchronized void saveCheckState(String sourceName, CheckState state) {
        ConfigurationSection entry = entrySection(sourceName, true);
        if (entry == null || state == null) {
            return;
        }
        entry.set(LATEST_VERSION_NODE, state.latestVersion());
        entry.set(LATEST_BUILD_NODE, state.latestBuild() > 0 ? state.latestBuild() : null);
        entry.set(CHECKED_AT_NODE, state.checkedAt());
        entry.set(RESULT_NODE, state.result().name());
        entry.set(ERROR_NODE, state.error());
        saveInternal();
    }

    public synchronized Optional<CheckState> findCheckState(String sourceName) {
        ConfigurationSection entry = entrySection(sourceName, false);
        if (entry == null || !entry.contains(CHECKED_AT_NODE)) {
            return Optional.empty();
        }
        CheckResult result = CheckResult.parse(entry.getString(RESULT_NODE));
        return Optional.of(new CheckState(
                entry.getString(LATEST_VERSION_NODE),
                entry.getInt(LATEST_BUILD_NODE, 0),
                entry.getLong(CHECKED_AT_NODE),
                result,
                entry.getString(ERROR_NODE)));
    }

    public synchronized Optional<PluginState> find(String pluginName) {
        ConfigurationSection section = entrySection(pluginName, false);
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

    /**
     * Whether anything was ever recorded here - the marker of an installation
     * that has been running, as opposed to a fresh one.
     */
    public synchronized boolean hasAnyState() {
        ConfigurationSection plugins = pluginsSection(false);
        return plugins != null && !plugins.getKeys(false).isEmpty();
    }

    public synchronized boolean hasPluginInfo(String pluginName) {
        return find(pluginName).isPresent();
    }

    public synchronized int getStoredBuild(String pluginName) {
        return find(pluginName).map(PluginState::build).orElse(-1);
    }

    public synchronized String getStoredVersion(String pluginName) {
        return find(pluginName).map(PluginState::version).orElse(null);
    }

    public synchronized void saveLatestBuild(String pluginName, int build, String version) {
        savePluginState(pluginName, build, version);
    }

    public synchronized void savePluginState(String pluginName, Integer build, String version) {
        if (pluginName == null || pluginName.isBlank()) {
            return;
        }

        ConfigurationSection entry = entrySection(pluginName, true);
        if (entry == null) {
            return;
        }
        entry.set(BUILD_NODE, build);
        entry.set(VERSION_NODE, version);
        if (build == null && version == null) {
            pluginsSection(true).set(pluginName, null);
        }

        saveInternal();
    }

    private ConfigurationSection pluginsSection(boolean create) {
        ConfigurationSection section = configuration.getConfigurationSection(ROOT_NODE);
        if (section == null && create) {
            section = configuration.createSection(ROOT_NODE);
        }
        return section;
    }

    /**
     * Resolves a plugin's entry by raw key.
     *
     * <p>Building the path as {@code "plugins." + name} let a dot inside a
     * source name split it into nested sections, so "my.plugin" was stored as
     * plugins -> my -> plugin and the schema check removed it again on the next
     * start. Addressing the section directly keeps the name intact.</p>
     */
    private ConfigurationSection entrySection(String pluginName, boolean create) {
        if (pluginName == null || pluginName.isBlank()) {
            return null;
        }
        ConfigurationSection plugins = pluginsSection(create);
        if (plugins == null) {
            return null;
        }
        ConfigurationSection entry = plugins.getConfigurationSection(pluginName);
        if (entry == null && create) {
            entry = plugins.createSection(pluginName);
        }
        return entry;
    }

    private synchronized void saveInternal() {
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

    /** Outcome of the last check for one source. */
    public enum CheckResult {
        /** A new build was found and installed. */
        UPDATED,
        /** The source answered, and what it offers is already on disk. */
        UP_TO_DATE,
        /** The check itself failed - network, HTTP status, bad response. */
        FAILED,
        /**
         * Something newer exists, and the installation was refused: the file at
         * the destination belongs to another plugin, the plugin is already
         * installed under a different name, the download was not the artifact
         * it claimed to be, or the previous file could not be secured first.
         *
         * <p>Reported as {@link #UP_TO_DATE} before, which is how a guard that
         * kept stopping an install stayed invisible in {@code /nu2l status}.
         * {@link #parse(String)} answers {@code UP_TO_DATE} for names it does
         * not know, so an older jar reading this back degrades quietly.</p>
         */
        HELD;

        static CheckResult parse(String value) {
            if (value == null) {
                return UP_TO_DATE;
            }
            for (CheckResult candidate : values()) {
                if (candidate.name().equalsIgnoreCase(value)) {
                    return candidate;
                }
            }
            return UP_TO_DATE;
        }
    }

    public record CheckState(String latestVersion, int latestBuild, long checkedAt,
                             CheckResult result, String error) {
    }
}
