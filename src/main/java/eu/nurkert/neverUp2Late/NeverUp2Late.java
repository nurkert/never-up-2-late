package eu.nurkert.neverUp2Late;

import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import org.bukkit.plugin.java.JavaPlugin;

public final class NeverUp2Late extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        PersistentPluginHandler.getInstance();
        UpdateHandler.getInstance();
        getServer().getPluginManager().registerEvents(InstallationHandler.getInstance(), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    private static NeverUp2Late plugin;

    public NeverUp2Late() {
        plugin = this;
    }

    /**
     * @return instance of this Spigot-/JavaPlugin
     */
    public static NeverUp2Late getInstance() {
        return plugin;
    }
}
