package eu.nurkert.neverUp2Late;

import eu.nurkert.neverUp2Late.command.NeverUp2LateCommand;
import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.VersionComparator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class NeverUp2Late extends JavaPlugin {

    private PluginContext context;

    @Override
    public void onEnable() {
        FileConfiguration configuration = getConfig();

        PersistentPluginHandler persistentPluginHandler = new PersistentPluginHandler(this);
        InstallationHandler installationHandler = new InstallationHandler(this);
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
                versionComparator
        );

        context = new PluginContext(
                this,
                getServer().getScheduler(),
                configuration,
                persistentPluginHandler,
                updateHandler,
                installationHandler,
                updateSourceRegistry
        );

        updateHandler.start();
        getServer().getPluginManager().registerEvents(installationHandler, this);

        QuickInstallCoordinator coordinator = new QuickInstallCoordinator(context);
        NeverUp2LateCommand command = new NeverUp2LateCommand(coordinator);
        PluginCommand pluginCommand = getCommand("nu2l");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().warning("Failed to register /nu2l command; entry missing in plugin.yml");
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    public PluginContext getContext() {
        return context;
    }
}
