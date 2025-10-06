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
import eu.nurkert.neverUp2Late.persistence.SetupStateRepository;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;
import eu.nurkert.neverUp2Late.persistence.SetupStateRepository.SetupPhase;
import eu.nurkert.neverUp2Late.setup.InitialSetupManager;

import java.nio.file.Path;

public final class NeverUp2Late extends JavaPlugin {

    private PluginContext context;
    private InitialSetupManager setupManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration configuration = getConfig();

        UpdateStateRepository updateStateRepository = UpdateStateRepository.forPlugin(this);
        PluginUpdateSettingsRepository updateSettingsRepository = PluginUpdateSettingsRepository.forPlugin(this);
        SetupStateRepository setupStateRepository = SetupStateRepository.forPlugin(this);
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
            pluginLifecycleManager.registerPlugin(this);
        } else {
            getLogger().fine("Plugin lifecycle management is disabled (pluginLifecycle.autoManage=false).");
        }

        InstallationHandler installationHandler = new InstallationHandler(this, pluginLifecycleManager, updateSettingsRepository);
        UpdateSourceRegistry updateSourceRegistry = new UpdateSourceRegistry(getLogger(), configuration);
        int maxBackups = Math.max(0, configuration.getInt("backups.maxCount", 5));
        Path backupsDirectory = getDataFolder().toPath().resolve("backups");
        ArtifactDownloader artifactDownloader = new ArtifactDownloader(backupsDirectory, maxBackups);
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
                updateSettingsRepository,
                setupStateRepository,
                artifactDownloader
        );

        AnvilTextPrompt anvilTextPrompt = new AnvilTextPrompt(this);
        setupManager = new InitialSetupManager(context, setupStateRepository, anvilTextPrompt);
        boolean skipWizard = configuration.getBoolean("setup.skipWizard", false);
        SetupPhase currentPhase = setupStateRepository.getPhase();
        if (currentPhase == SetupPhase.COMPLETED) {
            updateHandler.start();
        } else if (skipWizard) {
            setupManager.completeSetup(getServer().getConsoleSender());
        } else {
            setupManager.enableSetupMode();
            getLogger().info("NeverUp2Late wartet auf die erste Einrichtung. Spieler mit neverup2late.setup erhalten automatisch eine Anleitung.");
        }
        getServer().getPluginManager().registerEvents(installationHandler, this);

        QuickInstallCoordinator coordinator = new QuickInstallCoordinator(context);
        PluginOverviewGui overviewGui = new PluginOverviewGui(context, coordinator, anvilTextPrompt);
        NeverUp2LateCommand command = new NeverUp2LateCommand(coordinator, overviewGui, setupManager, context);
        PluginCommand pluginCommand = getCommand("nu2l");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().warning("Failed to register /nu2l command; entry missing in plugin.yml");
        }

        getServer().getPluginManager().registerEvents(anvilTextPrompt, this);
        getServer().getPluginManager().registerEvents(overviewGui, this);
        getServer().getPluginManager().registerEvents(setupManager, this);
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
