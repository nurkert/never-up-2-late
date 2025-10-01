package eu.nurkert.neverUp2Late;

import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.fetcher.GeyserFetcher;
import eu.nurkert.neverUp2Late.fetcher.PaperFetcher;
import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class NeverUp2Late extends JavaPlugin {

    private PluginContext context;

    @Override
    public void onEnable() {
        FileConfiguration configuration = getConfig();

        PersistentPluginHandler persistentPluginHandler = new PersistentPluginHandler(this);
        InstallationHandler installationHandler = new InstallationHandler(getServer());

        Map<String, UpdateFetcher> fetchers = Map.ofEntries(
                Map.entry("paper", new PaperFetcher(configuration.getBoolean("ignoreUnstable"))),
                Map.entry("geyser", new GeyserFetcher())
        );

        UpdateHandler updateHandler = new UpdateHandler(
                this,
                getServer().getScheduler(),
                configuration,
                persistentPluginHandler,
                installationHandler,
                fetchers
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
