package eu.nurkert.neverUp2Late.persistence;

import eu.nurkert.neverUp2Late.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

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
 *
 * <p>{@link #moveSelfUpdateSourceToGithub()} is the single deliberate exception
 * to the first rule, and says at its own definition why it earns one. It swaps
 * the delivery channel of the self-update, never the decision whether to
 * self-update at all.</p>
 */
public class ConfigurationUpgrader {

    /** Bump when a new step is added below. */
    private static final int CURRENT_VERSION = 2;
    private static final String VERSION_NODE = "configVersion";
    private static final String STATE_FILE = "config-upgrade.yml";
    private static final String SOURCES_NODE = "updates.sources";
    static final String SELF_SOURCE_NAME = "neverup2late";

    private final FileConfiguration configuration;
    private final File stateFile;
    private final Logger logger;

    public ConfigurationUpgrader(FileConfiguration configuration, File dataFolder, Logger logger) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.stateFile = dataFolder == null ? null : new File(dataFolder, STATE_FILE);
        this.logger = logger;
    }

    /**
     * @return {@code true} when the configuration changed and should be saved
     */
    public boolean upgrade() {
        int from = appliedVersion();
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
        if (from < 2) {
            changed |= moveSelfUpdateSourceToGithub();
        }

        recordApplied();
        if (changed) {
            log(Level.INFO, "Your config.yml was extended with settings added since it was written."
                    + " Existing values were left untouched.");
        }
        // Only report a change when something really changed. Saving otherwise
        // rewrites a file that needed nothing, and a rewrite drops the comments
        // inside updates.sources - the parked, commented-out source an operator
        // keeps there, and the worked examples this plugin ships.
        return changed;
    }

    /**
     * Which upgrade step this installation has already had.
     *
     * <p>Kept in a file the plugin owns. Writing it into config.yml would mean
     * touching the operator's file even when there was nothing to add.</p>
     */
    private int appliedVersion() {
        // Honour the marker 2.5.1 wrote into config.yml, so that release's
        // installations are not upgraded a second time.
        int fromConfig = configuration.isSet(VERSION_NODE) ? configuration.getInt(VERSION_NODE, 0) : 0;
        if (stateFile == null) {
            return fromConfig;
        }
        return Math.max(fromConfig, YamlFiles.loadOrQuarantine(stateFile, logger).getInt(VERSION_NODE, 0));
    }

    private void recordApplied() {
        if (stateFile == null) {
            return;
        }
        try {
            YamlConfiguration state = YamlFiles.loadOrQuarantine(stateFile, logger);
            state.set(VERSION_NODE, CURRENT_VERSION);
            File parent = stateFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                log(Level.WARNING, "Could not create " + parent + "; the config upgrade may run again.");
                return;
            }
            state.save(stateFile);
        } catch (IOException ex) {
            log(Level.WARNING, "Could not record the config upgrade (" + ex.getMessage()
                    + "); it may run again on the next start.");
        }
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

    /**
     * Moves an existing self-update source onto the GitHub releases.
     *
     * <p>This is the one step that rewrites a value the operator already had,
     * so it needs a reason. Early installs point their self-update at the
     * SpigotMC resource. That delivers the same plugin, but by a route that
     * skips the only safety net this project has: every tag is published as a
     * pre-release first and only becomes a real release after a soak period, so
     * a build that breaks the updater can still be pulled back. On the SpigotMC
     * route there is nothing to pull back from - and a server whose
     * NeverUp2Late no longer loads cannot fetch the fix by itself.</p>
     *
     * <p>It is therefore a change of delivery channel for the same artifact,
     * not a change of anybody's mind. Whether self-updating happens at all
     * stays exactly as configured: a disabled source stays disabled.</p>
     */
    private boolean moveSelfUpdateSourceToGithub() {
        if (!configuration.isSet(SOURCES_NODE)) {
            // No list of their own - the packaged defaults already use GitHub.
            return false;
        }

        ConfigurationSection section = configuration.getConfigurationSection(SOURCES_NODE);
        if (section != null && !section.getKeys(false).isEmpty()) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                if (!SELF_SOURCE_NAME.equalsIgnoreCase(entry.getString("name", key))) {
                    continue;
                }
                if (isGithubRelease(entry.getString("type"))) {
                    return false;
                }
                entry.set("type", "githubRelease");
                entry.set("filename", "NeverUp2Late.jar");
                // Clear first: the old options hold resourceId and friends,
                // which mean nothing to the GitHub fetcher and would linger.
                entry.set("options", null);
                entry.createSection("options", githubSelfUpdateOptions());
                logMovedToGithub();
                return true;
            }
            return false;
        }

        List<?> raw = configuration.getList(SOURCES_NODE);
        if (raw != null && raw.stream().anyMatch(item -> !(item instanceof Map))) {
            // Same reasoning as when adding the source: writing a filtered list
            // back would delete whatever getMapList silently dropped.
            log(Level.WARNING, "updates.sources holds entries that are not sources;"
                    + " leaving the self-update source on its current type.");
            return false;
        }

        List<Map<?, ?>> entries = configuration.getMapList(SOURCES_NODE);
        List<Map<String, Object>> rewritten = new ArrayList<>(entries.size());
        boolean changed = false;
        for (Map<?, ?> original : entries) {
            Map<String, Object> entry = new LinkedHashMap<>();
            original.forEach((k, v) -> entry.put(Objects.toString(k, ""), v));
            if (!changed
                    && SELF_SOURCE_NAME.equalsIgnoreCase(Objects.toString(entry.get("name"), ""))) {
                if (isGithubRelease(Objects.toString(entry.get("type"), ""))) {
                    return false;
                }
                entry.put("type", "githubRelease");
                entry.put("filename", "NeverUp2Late.jar");
                entry.put("options", githubSelfUpdateOptions());
                changed = true;
            }
            rewritten.add(entry);
        }
        if (!changed) {
            return false;
        }
        configuration.set(SOURCES_NODE, rewritten);
        logMovedToGithub();
        return true;
    }

    private static boolean isGithubRelease(String type) {
        return type != null && "githubRelease".equalsIgnoreCase(type.trim());
    }

    private static Map<String, Object> githubSelfUpdateOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("owner", "nurkert");
        options.put("repository", "never-up-2-late");
        options.put("assetPattern", "^NeverUp2Late\\.jar$");
        options.put("installedPlugin", "NeverUp2Late");
        return options;
    }

    private void logMovedToGithub() {
        log(Level.INFO, "NeverUp2Late now updates itself from its GitHub releases instead of SpigotMC."
                + " Releases are published there as pre-releases first and only go live after a soak"
                + " period, so a broken build can still be withdrawn. Whether it updates at all is"
                + " unchanged - the 'enabled' flag on the '" + SELF_SOURCE_NAME + "' source still decides.");
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
