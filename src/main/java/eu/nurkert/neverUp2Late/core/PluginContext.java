package eu.nurkert.neverUp2Late.core;

import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * Central registry for plugin level services and shared Bukkit infrastructure components.
 */
public class PluginContext {

    private final JavaPlugin plugin;
    private final BukkitScheduler scheduler;
    private final FileConfiguration configuration;

    private final PersistentPluginHandler persistentPluginHandler;
    private final UpdateHandler updateHandler;
    private final InstallationHandler installationHandler;
    private final eu.nurkert.neverUp2Late.update.UpdateSourceRegistry updateSourceRegistry;

    public PluginContext(JavaPlugin plugin,
                         BukkitScheduler scheduler,
                         FileConfiguration configuration,
                         PersistentPluginHandler persistentPluginHandler,
                         UpdateHandler updateHandler,
                         InstallationHandler installationHandler,
                         eu.nurkert.neverUp2Late.update.UpdateSourceRegistry updateSourceRegistry) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.persistentPluginHandler = persistentPluginHandler;
        this.updateHandler = updateHandler;
        this.installationHandler = installationHandler;
        this.updateSourceRegistry = updateSourceRegistry;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public BukkitScheduler getScheduler() {
        return scheduler;
    }

    public FileConfiguration getConfiguration() {
        return configuration;
    }

    public PersistentPluginHandler getPersistentPluginHandler() {
        return persistentPluginHandler;
    }

    public UpdateHandler getUpdateHandler() {
        return updateHandler;
    }

    public InstallationHandler getInstallationHandler() {
        return installationHandler;
    }

    public eu.nurkert.neverUp2Late.update.UpdateSourceRegistry getUpdateSourceRegistry() {
        return updateSourceRegistry;
    }
}
