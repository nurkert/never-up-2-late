package eu.nurkert.neverUp2Late.core;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.SetupStateRepository;
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
    private final PluginLifecycleManager pluginLifecycleManager;
    private final PluginUpdateSettingsRepository pluginUpdateSettingsRepository;
    private final SetupStateRepository setupStateRepository;
    private final ArtifactDownloader artifactDownloader;

    public PluginContext(JavaPlugin plugin,
                         BukkitScheduler scheduler,
                         FileConfiguration configuration,
                         PersistentPluginHandler persistentPluginHandler,
                         UpdateHandler updateHandler,
                         InstallationHandler installationHandler,
                         eu.nurkert.neverUp2Late.update.UpdateSourceRegistry updateSourceRegistry,
                         PluginLifecycleManager pluginLifecycleManager,
                         PluginUpdateSettingsRepository pluginUpdateSettingsRepository,
                         SetupStateRepository setupStateRepository,
                         ArtifactDownloader artifactDownloader) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.persistentPluginHandler = persistentPluginHandler;
        this.updateHandler = updateHandler;
        this.installationHandler = installationHandler;
        this.updateSourceRegistry = updateSourceRegistry;
        this.pluginLifecycleManager = pluginLifecycleManager;
        this.pluginUpdateSettingsRepository = pluginUpdateSettingsRepository;
        this.setupStateRepository = setupStateRepository;
        this.artifactDownloader = artifactDownloader;
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

    public PluginLifecycleManager getPluginLifecycleManager() {
        return pluginLifecycleManager;
    }

    public PluginUpdateSettingsRepository getPluginUpdateSettingsRepository() {
        return pluginUpdateSettingsRepository;
    }

    public SetupStateRepository getSetupStateRepository() {
        return setupStateRepository;
    }

    public ArtifactDownloader getArtifactDownloader() {
        return artifactDownloader;
    }
}
