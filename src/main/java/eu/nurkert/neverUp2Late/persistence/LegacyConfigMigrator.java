package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Migrates legacy plugin state stored in {@code config.yml} into the dedicated
 * {@code plugins.yml} persistence file.
 */
public class LegacyConfigMigrator {

    private static final String PLUGINS_NODE = "plugins";
    private static final String BUILD_NODE = "build";
    private static final String VERSION_NODE = "version";

    private final FileConfiguration configuration;
    private final UpdateStateRepository repository;
    private final Logger logger;

    public LegacyConfigMigrator(FileConfiguration configuration,
                                UpdateStateRepository repository,
                                Logger logger) {
        this.configuration = configuration;
        this.repository = repository;
        this.logger = logger;
    }

    /**
     * Migrates legacy plugin state entries from {@code config.yml} to the
     * persistent {@code plugins.yml} file.
     *
     * @return {@code true} if the configuration was mutated and should be saved
     */
    public boolean migrate() {
        ConfigurationSection pluginsSection = configuration.getConfigurationSection(PLUGINS_NODE);
        if (pluginsSection == null) {
            return false;
        }

        Set<String> pluginKeys = new LinkedHashSet<>(pluginsSection.getKeys(false));
        if (pluginKeys.isEmpty()) {
            configuration.set(PLUGINS_NODE, null);
            return true;
        }

        boolean mutated = false;
        int migratedPlugins = 0;

        for (String pluginKey : pluginKeys) {
            mutated = true;

            LegacyPluginState state = readState(pluginsSection, pluginKey);
            if (state != null) {
                repository.savePluginState(pluginKey, state.build(), state.version());
                migratedPlugins++;
            }

            pluginsSection.set(pluginKey, null);
        }

        if (pluginsSection.getKeys(false).isEmpty()) {
            configuration.set(PLUGINS_NODE, null);
            mutated = true;
        }

        if (migratedPlugins > 0) {
            logger.log(Level.INFO, "Migrated {0} legacy plugin entr{1} from config.yml to plugins.yml.",
                    new Object[]{migratedPlugins, migratedPlugins == 1 ? "y" : "ies"});
        }

        return mutated;
    }

    private LegacyPluginState readState(ConfigurationSection pluginsSection, String pluginKey) {
        ConfigurationSection pluginSection = pluginsSection.getConfigurationSection(pluginKey);
        Integer build = null;
        String version = null;

        if (pluginSection != null) {
            if (pluginSection.contains(BUILD_NODE)) {
                Object buildValue = pluginSection.get(BUILD_NODE);
                build = toInteger(buildValue, pluginKey);
            }
            if (pluginSection.contains(VERSION_NODE)) {
                Object versionValue = pluginSection.get(VERSION_NODE);
                if (versionValue != null) {
                    version = versionValue.toString();
                }
            }
        } else {
            Object value = pluginsSection.get(pluginKey);
            if (value instanceof Number number) {
                build = number.intValue();
            } else if (value instanceof String string) {
                version = string;
            } else if (value != null) {
                logger.log(Level.WARNING,
                        "Ignoring unsupported legacy entry {0} in config.yml during migration.", pluginKey);
            }
        }

        if (build == null && (version == null || version.isBlank())) {
            return null;
        }

        if (version != null && version.isBlank()) {
            version = null;
        }

        return new LegacyPluginState(build, version);
    }

    private Integer toInteger(Object value, String pluginKey) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ex) {
                logger.log(Level.WARNING,
                        "Unable to parse build value '{0}' for legacy plugin entry {1}",
                        new Object[]{string, pluginKey});
            }
        } else if (value != null) {
            logger.log(Level.WARNING,
                    "Ignoring non-numeric build value for legacy plugin entry {0}", pluginKey);
        }
        return null;
    }

    private record LegacyPluginState(Integer build, String version) {
    }
}
