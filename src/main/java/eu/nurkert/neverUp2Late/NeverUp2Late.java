package eu.nurkert.neverUp2Late;

import eu.nurkert.neverUp2Late.command.NeverUp2LateCommand;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.PluginOverviewGui;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.PortalVelocityListener;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.plugin.PluginManagerApi;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.VersionComparator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import eu.nurkert.neverUp2Late.persistence.ConfigurationUpgrader;
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
        java.io.File configFile = new java.io.File(getDataFolder(), "config.yml");
        // Whether this server ran NeverUp2Late before, decided before
        // saveDefaultConfig() can create the file and blur the answer.
        boolean existingInstallation = configFile.isFile();
        saveDefaultConfig();
        FileConfiguration configuration = getConfig();

        // Bukkit answers an unreadable config.yml with an EMPTY configuration -
        // it logs the parse error and hands back nothing. Writing that back
        // would replace the operator's file with a handful of generated keys, so
        // nothing here may save while the file cannot be read.
        boolean configurationReadable = isReadable(configFile);
        if (!configurationReadable) {
            getLogger().severe("config.yml could not be read - leaving it untouched."
                    + " Fix the reported YAML error; NeverUp2Late runs on defaults until then.");
        }

        UpdateStateRepository updateStateRepository = UpdateStateRepository.forPlugin(this);
        PluginUpdateSettingsRepository updateSettingsRepository = PluginUpdateSettingsRepository.forPlugin(this);
        SetupStateRepository setupStateRepository = SetupStateRepository.forPlugin(this);
        LegacyConfigMigrator migrator = new LegacyConfigMigrator(configuration, updateStateRepository, updateSettingsRepository, getLogger());
        boolean configurationChanged = migrator.migrate();
        if (configurationReadable) {
            configurationChanged |= new ConfigurationUpgrader(configuration, getDataFolder(), getLogger()).upgrade();
        }
        if (configurationChanged && configurationReadable) {
            saveConfig();
        }

        // An installation that predates the setup gate has been updating for
        // months without ever opening the wizard, so its phase is still
        // UNINITIALISED. Pausing it now would silently stop the updates it has
        // been doing all along; a recorded update history is proof enough that
        // this server is set up.
        // Three conditions, all needed. A config.yml that predates this start
        // proves the plugin ran here before; recorded update history proves it
        // actually did the work (an install that never finished its setup has
        // none, because the gate stopped it); and only UNINITIALISED is rescued,
        // so a wizard someone is halfway through is never closed behind them.
        if (existingInstallation
                && setupStateRepository.getPhase() == SetupPhase.UNINITIALISED
                && updateStateRepository.hasAnyState()) {
            getLogger().info("Existing installation detected - keeping updates running without the setup wizard.");
            setupStateRepository.setPhase(SetupPhase.COMPLETED);
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
                updateSettingsRepository,
                setupStateRepository
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
            // The scheduler runs, but every check bails out until the setup is
            // confirmed - see UpdateHandler#checkForUpdates. Nothing is
            // downloaded, installed or restarted before the operator has said
            // what should be managed.
            updateHandler.start();
            setupManager.enableSetupMode();
            getLogger().info("NeverUp2Late is installed but not set up yet - nothing will be updated until you run /nu2l setup.");
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
        getServer().getPluginManager().registerEvents(new PortalVelocityListener(this), this);
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

    /** @return whether the file parses; an absent file counts as readable. */
    private boolean isReadable(java.io.File configFile) {
        if (!configFile.isFile()) {
            return true;
        }
        try {
            new org.bukkit.configuration.file.YamlConfiguration().load(configFile);
            return true;
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "config.yml is not valid YAML", ex);
            return false;
        }
    }

    public PluginContext getContext() {
        return context;
    }
}
