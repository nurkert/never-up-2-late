package eu.nurkert.neverUp2Late;

import eu.nurkert.neverUp2Late.command.NeverUp2LateCommand;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.ConfigurationHelper;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.PluginOverviewGui;
import eu.nurkert.neverUp2Late.gui.SettingsGui;
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
        LegacyConfigMigrator migrator = new LegacyConfigMigrator(configuration, updateStateRepository, getLogger());
        if (migrator.migrate()) {
            saveConfig();
        }

        PersistentPluginHandler persistentPluginHandler = new PersistentPluginHandler(updateStateRepository);
        PluginUpdateSettingsRepository updateSettingsRepository = PluginUpdateSettingsRepository.forPlugin(this);

        boolean migratedSources = configuration.getConfigurationSection("updates.sources") == null
                && configuration.getMapList("updates.sources") != null
                && !configuration.getMapList("updates.sources").isEmpty();
        ConfigurationHelper.ensureSourcesSection(configuration);
        if (migratedSources) {
            saveConfig();
        }

        boolean lifecycleEnabled = configuration.getBoolean("pluginLifecycle.autoManage", false);
        boolean autoLoadOnInstall = configuration.getBoolean("pluginLifecycle.autoLoadOnInstall", true);
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

        InstallationHandler installationHandler = new InstallationHandler(
                this,
                pluginLifecycleManager,
                updateSettingsRepository,
                autoLoadOnInstall
        );
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
        SettingsGui settingsGui = new SettingsGui(context);
        PluginOverviewGui overviewGui = new PluginOverviewGui(context, coordinator, settingsGui);
        settingsGui.setOverviewGui(overviewGui);
        NeverUp2LateCommand command = new NeverUp2LateCommand(coordinator, overviewGui);
        PluginCommand pluginCommand = getCommand("nu2l");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().warning("Failed to register /nu2l command; entry missing in plugin.yml");
        }

        getServer().getPluginManager().registerEvents(overviewGui, this);
        getServer().getPluginManager().registerEvents(settingsGui, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    public PluginContext getContext() {
        return context;
    }
}
