package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.NeverUp2Late;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class PersistentPluginHandler {
    // Singleton instance
    private static PersistentPluginHandler instance = new PersistentPluginHandler();

    private PluginsFile pluginsFile;
    private FileConfiguration config;

    private PersistentPluginHandler() {
        pluginsFile = new PluginsFile();
        config = pluginsFile.getConfig();
    }

    /**
     * @return the singleton instance
     */
    public static PersistentPluginHandler getInstance() {
        return instance;
    }

    public void set(String path, Object value) {
        config.set(path, value);
        pluginsFile.save();
    }
    public int getBuild(String path) {
        return config.get(path) != null ? config.getInt(path) :-1;
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
        private File dataFolder, pluginsYML;
        private FileConfiguration plugins;

        public PluginsFile() {
            init();
        }

        private void init() {
            dataFolder = NeverUp2Late.getInstance().getDataFolder();

            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            pluginsYML = new File(dataFolder, filename);
            if (!pluginsYML.exists()) {
                try {
                    pluginsYML.createNewFile();
                    NeverUp2Late.getInstance().saveDefaultConfig();
                } catch (IOException e) {
                    e.printStackTrace();
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
                e.printStackTrace();
            }
        }
    }
}
