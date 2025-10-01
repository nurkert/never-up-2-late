package eu.nurkert.neverUp2Late.handlers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PersistentPluginHandler {

    private final PluginsFile pluginsFile;
    private final FileConfiguration config;

    public PersistentPluginHandler(JavaPlugin plugin) {
        pluginsFile = new PluginsFile(plugin);
        config = pluginsFile.getConfig();
    }

    public void set(String path, Object value) {
        config.set(path, value);
        pluginsFile.save();
    }
    public int getBuild(String path) {
        return config.get(path) != null ? config.getInt(path) : -1;
    }

    /**
     * Checks if the given plugin entry exists.
     *
     * @param pluginName The name of the plugin.
     * @return true if an entry exists, false otherwise.
     */
    public boolean hasPluginInfo(String pluginName) {
        return config.contains("plugins." + pluginName);
    }

    /**
     * Inner class to manage the plugins.yml file.
     */
    public static class PluginsFile {

        private final String filename = "plugins.yml";
        private final JavaPlugin plugin;
        private final Logger logger;
        private File dataFolder;
        private File pluginsYML;
        private FileConfiguration plugins;

        public PluginsFile(JavaPlugin plugin) {
            this.plugin = plugin;
            this.logger = plugin.getLogger();
            init();
        }

        private void init() {
            dataFolder = plugin.getDataFolder();

            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.log(Level.WARNING, "Could not create plugin data folder at {0}", dataFolder.getAbsolutePath());
            }

            pluginsYML = new File(dataFolder, filename);
            if (!pluginsYML.exists()) {
                try {
                    if (!pluginsYML.createNewFile()) {
                        logger.log(Level.WARNING, "Unable to create {0}", pluginsYML.getAbsolutePath());
                    }
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Failed to create " + filename, e);
                }
            }

            plugins = YamlConfiguration.loadConfiguration(pluginsYML);
        }

        public FileConfiguration getConfig() {
            return plugins;
        }

        public void save() {
            try {
                plugins.save(pluginsYML);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to save " + filename, e);
            }
        }
    }
}
