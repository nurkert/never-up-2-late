package eu.nurkert.neverUp2Late;

import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class NeverUp2Late extends JavaPlugin {

    private PluginContext context;

    @Override
    public void onEnable() {
        FileConfiguration configuration = getConfig();

        PersistentPluginHandler persistentPluginHandler = new PersistentPluginHandler(this);
        InstallationHandler installationHandler = new InstallationHandler(getServer());
        UpdateSourceRegistry updateSourceRegistry = new UpdateSourceRegistry(getLogger(), configuration);

        UpdateHandler updateHandler = new UpdateHandler(
                this,
                getServer().getScheduler(),
                configuration,
                persistentPluginHandler,
                installationHandler,
                updateSourceRegistry
        );

        context = new PluginContext(
                this,
                getServer().getScheduler(),
                configuration,
                persistentPluginHandler,
                updateHandler,
                installationHandler
        );

        updateHandler.start();
        getServer().getPluginManager().registerEvents(installationHandler, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    public PluginContext getContext() {
        return context;
    }
}
