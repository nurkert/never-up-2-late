package eu.nurkert.neverUp2Late.persistence;

import eu.nurkert.neverUp2Late.util.YamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plugin updates that are on disk but not yet proven to load.
 *
 * <p>An update only shows whether it works when the server starts with it,
 * which is usually a restart away - long after the process that installed it
 * is gone. This file carries the question across that restart.</p>
 */
public class PendingVerificationRepository {

    private static final String FILE_NAME = "pending-verification.yml";

    private final File stateFile;
    private final Logger logger;
    private final YamlConfiguration configuration;

    public PendingVerificationRepository(File dataFolder, Logger logger) {
        this.logger = logger;
        this.stateFile = new File(dataFolder, FILE_NAME);
        this.configuration = YamlFiles.loadOrQuarantine(stateFile, logger);
    }

    /**
     * @param pluginName  the name the updated plugin declares in its plugin.yml
     * @param sourceName  the update source that installed it
     * @param destination where the new jar lives (or will, once the server swaps it in)
     * @param backup      the copy of the jar it replaced
     * @param version     the version that was installed, or {@code null}
     */
    public record Entry(String pluginName, String sourceName, Path destination, Path backup, String version) {
        public Entry {
            Objects.requireNonNull(pluginName, "pluginName");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(backup, "backup");
        }
    }

    public synchronized void record(Entry entry) {
        String key = keyOf(entry.pluginName());
        configuration.set(key + ".plugin", entry.pluginName());
        configuration.set(key + ".source", entry.sourceName());
        configuration.set(key + ".destination", entry.destination().toString());
        configuration.set(key + ".backup", entry.backup().toString());
        configuration.set(key + ".version", entry.version());
        save();
    }

    public synchronized List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (String key : configuration.getKeys(false)) {
            ConfigurationSection section = configuration.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String plugin = section.getString("plugin");
            String destination = section.getString("destination");
            String backup = section.getString("backup");
            if (plugin == null || destination == null || backup == null) {
                continue;
            }
            entries.add(new Entry(plugin, section.getString("source"), Path.of(destination), Path.of(backup),
                    section.getString("version")));
        }
        return entries;
    }

    public synchronized void remove(String pluginName) {
        configuration.set(keyOf(pluginName), null);
        save();
    }

    private static String keyOf(String pluginName) {
        // Section paths treat dots as separators; plugin names may not.
        return pluginName.toLowerCase(Locale.ROOT).replace('.', '_');
    }

    private void save() {
        try {
            configuration.save(stateFile);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Could not save " + FILE_NAME + ": " + ex.getMessage());
        }
    }
}
