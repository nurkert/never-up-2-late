package eu.nurkert.neverUp2Late;

import eu.nurkert.neverUp2Late.command.NeverUp2LateCommand;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.PluginOverviewGui;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.plugin.PluginManagerApi;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.VersionComparator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import eu.nurkert.neverUp2Late.persistence.LegacyConfigMigrator;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;

public final class NeverUp2Late extends JavaPlugin {

    private PluginContext context;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration configuration = getConfig();

        UpdateStateRepository updateStateRepository = UpdateStateRepository.forPlugin(this);
        PluginUpdateSettingsRepository updateSettingsRepository = PluginUpdateSettingsRepository.forPlugin(this);
        LegacyConfigMigrator migrator = new LegacyConfigMigrator(configuration, updateStateRepository, updateSettingsRepository, getLogger());
        if (migrator.migrate()) {
            saveConfig();
        }

        PersistentPluginHandler persistentPluginHandler = new PersistentPluginHandler(updateStateRepository);
        boolean lifecycleEnabled = configuration.getBoolean("pluginLifecycle.autoManage", true);
        PluginLifecycleManager pluginLifecycleManager = null;
        if (lifecycleEnabled) {
            pluginLifecycleManager = new PluginManagerApi(
                    getServer().getPluginManager(),
                    getDataFolder().getParentFile(),
                    getLogger()
            );
            pluginLifecycleManager.registerLoadedPlugins(this);
        } else {
            getLogger().fine("Plugin lifecycle management is disabled (pluginLifecycle.autoManage=false).");
        }

        InstallationHandler installationHandler = new InstallationHandler(this, pluginLifecycleManager, updateSettingsRepository);
        UpdateSourceRegistry updateSourceRegistry = new UpdateSourceRegistry(getLogger(), configuration);
        ArtifactDownloader artifactDownloader = new ArtifactDownloader();
        VersionComparator versionComparator = new VersionComparator();

        UpdateHandler updateHandler = new UpdateHandler(
                this,
                getServer().getScheduler(),
                configuration,
                persistentPluginHandler,
                installationHandler,
                updateSourceRegistry,
                artifactDownloader,
                versionComparator,
                pluginLifecycleManager,
                updateSettingsRepository
        );

        context = new PluginContext(
                this,
                getServer().getScheduler(),
                configuration,
                persistentPluginHandler,
                updateHandler,
                installationHandler,
                updateSourceRegistry,
                pluginLifecycleManager,
                updateSettingsRepository
        );

        updateHandler.start();
        getServer().getPluginManager().registerEvents(installationHandler, this);

        QuickInstallCoordinator coordinator = new QuickInstallCoordinator(context);
        AnvilTextPrompt anvilTextPrompt = new AnvilTextPrompt(this);
        PluginOverviewGui overviewGui = new PluginOverviewGui(context, coordinator, anvilTextPrompt);
        NeverUp2LateCommand command = new NeverUp2LateCommand(coordinator, overviewGui);
        PluginCommand pluginCommand = getCommand("nu2l");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().warning("Failed to register /nu2l command; entry missing in plugin.yml");
        }

        getServer().getPluginManager().registerEvents(anvilTextPrompt, this);
        getServer().getPluginManager().registerEvents(overviewGui, this);
    }

    @Override
    public void onDisable() {
        if (context != null) {
            try {
                context.getUpdateHandler().stop();
            } catch (Exception ex) {
                getLogger().log(java.util.logging.Level.FINE, "Failed to stop update handler during shutdown", ex);
            }
        }
    }

    public PluginContext getContext() {
        return context;
    }
}
