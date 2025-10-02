package eu.nurkert.neverUp2Late.plugin;

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Represents a plugin that can be controlled via the {@link PluginLifecycleManager}.
 * Implementations wrap the underlying Bukkit plugin instance and provide
 * lifecycle operations such as load, unload, enable and disable.
 */
public interface ManagedPlugin {

    /**
     * @return the last known display name of the plugin.
     */
    String getName();

    /**
     * @return the absolute path of the plugin JAR that is managed.
     */
    Path getPath();

    /**
     * Updates the managed plugin instance. Implementations should update their
     * internal state to reflect the supplied plugin.
     */
    void attach(Plugin plugin);

    /**
     * @return the currently loaded Bukkit plugin, if any.
     */
    Optional<Plugin> getPlugin();

    /**
     * @return {@code true} if the plugin is currently loaded.
     */
    boolean isLoaded();

    /**
     * @return {@code true} if the plugin is enabled in Bukkit.
     */
    boolean isEnabled();

    void load() throws PluginLifecycleException;

    void enable() throws PluginLifecycleException;

    void disable() throws PluginLifecycleException;

    void unload() throws PluginLifecycleException;

    void reload() throws PluginLifecycleException;
}
