package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Brings an existing config.yml up to date with settings introduced later.
 *
 * <p>Bukkit writes the packaged configuration only when none exists, so a server
 * that installed NeverUp2Late once keeps its file forever and never learns about
 * anything added since - including the entry that lets the plugin update itself.</p>
 *
 * <p>Two rules make this safe to run on every start. Nothing that is already
 * present is ever changed: the values in that file are the operator's decisions,
 * even where they merely inherited an old default. And each step runs exactly
 * once, recorded by {@code configVersion}, so a setting somebody deliberately
 * removed does not reappear on the next boot.</p>
 */
public class ConfigurationUpgrader {

    /** Bump when a new step is added below. */
    private static final int CURRENT_VERSION = 1;
    private static final String VERSION_NODE = "configVersion";
    private static final String SOURCES_NODE = "updates.sources";
    static final String SELF_SOURCE_NAME = "neverup2late";

    private final FileConfiguration configuration;
    private final Logger logger;

    public ConfigurationUpgrader(FileConfiguration configuration, Logger logger) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.logger = logger;
    }

    /**
     * @return {@code true} when the configuration changed and should be saved
     */
    public boolean upgrade() {
        int from = configuration.getInt(VERSION_NODE, 0);
        if (from >= CURRENT_VERSION) {
            return false;
        }

        boolean changed = false;
        if (from < 1) {
            // Judge the file's age before adding anything to it - the very keys
            // that answer the question are among the ones about to be written.
            boolean recentFile = writtenByANewerVersion();
            changed |= addMissing("startupDelaySeconds", 60,
                    "how long the first check waits after the server started");
            changed |= addMissing("updates.respectManualRollback", true,
                    "leave a restored backup in place instead of updating over it again");
            changed |= addMissing("filenames." + SELF_SOURCE_NAME, "NeverUp2Late.jar", null);
            changed |= addSelfUpdateSource(recentFile);
        }

        configuration.set(VERSION_NODE, CURRENT_VERSION);
        if (changed) {
            log(Level.INFO, "Your config.yml was extended with settings added since it was written."
                    + " Existing values were left untouched.");
        }
        return true;
    }

    private boolean addMissing(String path, Object value, String description) {
        // isSet, not contains: contains also sees the defaults packaged in the
        // jar and would report every key as present.
        if (configuration.isSet(path)) {
            return false;
        }
        configuration.set(path, value);
        if (description != null) {
            log(Level.INFO, "Added " + path + " = " + value + " (" + description + ").");
        }
        return true;
    }

    /**
     * Adds the source that keeps NeverUp2Late itself current. Servers set up
     * before it existed have no such entry, so the plugin could never update
     * itself there - the one update it is best placed to handle.
     */
    private boolean addSelfUpdateSource(boolean recentFile) {
        if (hasSelfUpdateSource()) {
            return false;
        }
        if (!configuration.isSet(SOURCES_NODE)) {
            // No source list of their own: the packaged defaults apply, and
            // those already contain the self-update entry.
            return false;
        }
        if (isSourceListEmpty()) {
            // Emptied on purpose - the registry treats that as "manage nothing",
            // and quietly putting an enabled source back would restart updating
            // on a server that switched it off.
            log(Level.FINE, "updates.sources is empty; not adding the self-update source.");
            return false;
        }
        if (recentFile) {
            // The file already knows settings this release did not invent, so a
            // missing self-update entry was removed deliberately.
            log(Level.FINE, "Self-update source is absent from a recent config; leaving it that way.");
            return false;
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("owner", "nurkert");
        options.put("repository", "never-up-2-late");
        options.put("assetPattern", "^NeverUp2Late\\.jar$");
        options.put("installedPlugin", "NeverUp2Late");

        ConfigurationSection section = configuration.getConfigurationSection(SOURCES_NODE);
        if (section != null && !section.getKeys(false).isEmpty()) {
            ConfigurationSection entry = section.createSection(SELF_SOURCE_NAME);
            entry.set("name", SELF_SOURCE_NAME);
            entry.set("type", "githubRelease");
            entry.set("target", "plugins");
            entry.set("filename", "NeverUp2Late.jar");
            entry.set("enabled", true);
            // createSection, not set: a Map stored through set() stays a plain
            // value, so options.owner would not be readable as a path.
            entry.createSection("options", options);
        } else {
            List<?> raw = configuration.getList(SOURCES_NODE);
            if (raw != null && raw.stream().anyMatch(item -> !(item instanceof Map))) {
                // getMapList silently drops whatever is not a map, and writing
                // the filtered list back would delete it from the file.
                log(Level.WARNING, "updates.sources holds entries that are not sources;"
                        + " not adding the self-update source. Please add it by hand if you want it.");
                return false;
            }
            List<Map<?, ?>> entries = new ArrayList<>(configuration.getMapList(SOURCES_NODE));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", SELF_SOURCE_NAME);
            entry.put("type", "githubRelease");
            entry.put("target", "plugins");
            entry.put("filename", "NeverUp2Late.jar");
            entry.put("enabled", true);
            entry.put("options", options);
            entries.add(entry);
            configuration.set(SOURCES_NODE, entries);
        }

        log(Level.INFO, "NeverUp2Late now keeps itself up to date from its GitHub releases."
                + " Set enabled: false on the '" + SELF_SOURCE_NAME + "' source in config.yml to turn that off.");
        return true;
    }

    private boolean isSourceListEmpty() {
        ConfigurationSection section = configuration.getConfigurationSection(SOURCES_NODE);
        if (section != null) {
            return section.getKeys(false).isEmpty();
        }
        List<?> raw = configuration.getList(SOURCES_NODE);
        return raw == null || raw.isEmpty();
    }

    /**
     * Whether this file already carries settings introduced alongside the
     * self-update source. If it does, the entry is missing because somebody took
     * it out, not because their file is old.
     */
    private boolean writtenByANewerVersion() {
        return configuration.isSet("startupDelaySeconds")
                || configuration.isSet("updates.respectManualRollback");
    }

    private boolean hasSelfUpdateSource() {
        if (!configuration.isSet(SOURCES_NODE)) {
            // getMapList and getConfigurationSection both read through to the
            // packaged defaults, which do contain the entry - answering "yes"
            // for a file that has no source list at all.
            return false;
        }
        ConfigurationSection section = configuration.getConfigurationSection(SOURCES_NODE);
        if (section != null && !section.getKeys(false).isEmpty()) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                String name = entry != null ? entry.getString("name", key) : key;
                if (SELF_SOURCE_NAME.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }
        for (Map<?, ?> entry : configuration.getMapList(SOURCES_NODE)) {
            if (SELF_SOURCE_NAME.equalsIgnoreCase(Objects.toString(entry.get("name"), ""))) {
                return true;
            }
        }
        return false;
    }

    private void log(Level level, String message) {
        if (logger != null) {
            logger.log(level, message);
        }
    }
}
