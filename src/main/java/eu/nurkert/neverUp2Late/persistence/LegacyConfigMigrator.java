package eu.nurkert.neverUp2Late.persistence;

import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.UpdateBehaviour;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Migrates legacy plugin state stored in {@code config.yml} into the dedicated
 * {@code plugins.yml} persistence file and transfers per-plugin settings into
 * {@code plugin-settings.yml}.
 */
public class LegacyConfigMigrator {

    private static final String PLUGINS_NODE = "plugins";
    private static final String BUILD_NODE = "build";
    private static final String VERSION_NODE = "version";
    private static final String AUTO_UPDATE_NODE = "autoUpdate";
    private static final String BEHAVIOUR_NODE = "behaviour";
    private static final String RETENTION_NODE = "retainUpstreamFilename";

    private final FileConfiguration configuration;
    private final UpdateStateRepository repository;
    private final PluginUpdateSettingsRepository settingsRepository;
    private final Logger logger;

    public LegacyConfigMigrator(FileConfiguration configuration,
                                UpdateStateRepository repository,
                                PluginUpdateSettingsRepository settingsRepository,
                                Logger logger) {
        this.configuration = configuration;
        this.repository = repository;
        this.settingsRepository = settingsRepository;
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

            LegacyPluginData data = readData(pluginsSection, pluginKey);
            boolean migratedEntry = false;
            if (data != null) {
                LegacyPluginState state = data.state();
                if (state != null) {
                    repository.savePluginState(pluginKey, state.build(), state.version());
                    migratedEntry = true;
                }

                PluginUpdateSettings settings = data.settings();
                if (settings != null) {
                    settingsRepository.saveSettings(pluginKey, settings);
                    migratedEntry = true;
                }
            }

            if (migratedEntry) {
                migratedPlugins++;
            }

            pluginsSection.set(pluginKey, null);
        }

        if (pluginsSection.getKeys(false).isEmpty()) {
            configuration.set(PLUGINS_NODE, null);
            mutated = true;
        }

        if (migratedPlugins > 0) {
            logger.log(Level.INFO,
                    "Migrated {0} legacy plugin entr{1} from config.yml to dedicated persistence files.",
                    new Object[]{migratedPlugins, migratedPlugins == 1 ? "y" : "ies"});
        }

        return mutated;
    }

    private LegacyPluginData readData(ConfigurationSection pluginsSection, String pluginKey) {
        ConfigurationSection pluginSection = pluginsSection.getConfigurationSection(pluginKey);

        LegacyPluginState state = null;
        PluginUpdateSettings settings = null;

        if (pluginSection != null) {
            state = readStateFromSection(pluginSection, pluginKey);
            settings = readSettings(pluginSection, pluginKey);
        } else {
            state = readStateFromValue(pluginsSection.get(pluginKey), pluginKey);
        }

        if (state == null && settings == null) {
            return null;
        }

        return new LegacyPluginData(state, settings);
    }

    private LegacyPluginState readStateFromSection(ConfigurationSection pluginSection, String pluginKey) {
        Integer build = null;
        String version = null;

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

        return normaliseState(build, version);
    }

    private LegacyPluginState readStateFromValue(Object value, String pluginKey) {
        Integer build = null;
        String version = null;

        if (value instanceof Number number) {
            build = number.intValue();
        } else if (value instanceof String string) {
            version = string;
        } else if (value != null) {
            logger.log(Level.WARNING,
                    "Ignoring unsupported legacy entry {0} in config.yml during migration.", pluginKey);
        }

        return normaliseState(build, version);
    }

    private LegacyPluginState normaliseState(Integer build, String version) {
        if (build == null && (version == null || version.isBlank())) {
            return null;
        }

        if (version != null && version.isBlank()) {
            version = null;
        }

        return new LegacyPluginState(build, version);
    }

    private PluginUpdateSettings readSettings(ConfigurationSection pluginSection, String pluginKey) {
        Boolean autoUpdate = null;
        UpdateBehaviour behaviour = null;
        Boolean retainFilename = null;

        if (pluginSection.contains(AUTO_UPDATE_NODE)) {
            Object value = pluginSection.get(AUTO_UPDATE_NODE);
            autoUpdate = toBoolean(value, pluginKey, AUTO_UPDATE_NODE);
        }

        if (pluginSection.contains(BEHAVIOUR_NODE)) {
            String behaviourValue = pluginSection.getString(BEHAVIOUR_NODE);
            if (behaviourValue != null && !behaviourValue.isBlank()) {
                behaviour = UpdateBehaviour.parse(behaviourValue)
                        .orElseGet(() -> {
                            logger.log(Level.WARNING,
                                    "Ignoring unknown behaviour '{0}' for legacy plugin entry {1}",
                                    new Object[]{behaviourValue, pluginKey});
                            return null;
                        });
            }
        }

        if (pluginSection.contains(RETENTION_NODE)) {
            Object value = pluginSection.get(RETENTION_NODE);
            retainFilename = toBoolean(value, pluginKey, RETENTION_NODE);
        }

        if (autoUpdate == null && behaviour == null && retainFilename == null) {
            return null;
        }

        PluginUpdateSettings settings = PluginUpdateSettings.defaultSettings();
        boolean mutated = false;

        if (autoUpdate != null) {
            settings = new PluginUpdateSettings(autoUpdate, settings.behaviour(), settings.retainUpstreamFilename());
            mutated = true;
        }

        if (behaviour != null) {
            settings = new PluginUpdateSettings(settings.autoUpdateEnabled(), behaviour, settings.retainUpstreamFilename());
            mutated = true;
        }

        if (retainFilename != null) {
            settings = new PluginUpdateSettings(settings.autoUpdateEnabled(), settings.behaviour(), retainFilename);
            mutated = true;
        }

        return mutated ? settings : null;
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

    private Boolean toBoolean(Object value, String pluginKey, String field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
            logger.log(Level.WARNING,
                    "Unable to parse boolean value '{0}' for field {1} of legacy plugin entry {2}",
                    new Object[]{string, field, pluginKey});
            return null;
        }
        if (value != null) {
            logger.log(Level.WARNING,
                    "Ignoring non-boolean value for field {0} of legacy plugin entry {1}",
                    new Object[]{field, pluginKey});
        }
        return null;
    }

    private record LegacyPluginState(Integer build, String version) {
    }

    private record LegacyPluginData(LegacyPluginState state, PluginUpdateSettings settings) {
    }
}
