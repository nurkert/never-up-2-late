package eu.nurkert.neverUp2Late.plugin;

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Unified management interface that exposes lifecycle operations for plugins.
 * Implementations wrap the underlying platform specific APIs and normalise the
 * results so callers can work against a consistent abstraction.
 */
public interface PluginLifecycleManager {

    /**
     * Registers an already loaded plugin with the lifecycle manager.
     */
    void registerPlugin(Plugin plugin);

    /**
     * Registers all currently loaded plugins with the lifecycle manager while
     * skipping the provided reference (usually the manager plugin itself).
     */
    void registerLoadedPlugins(Plugin self);

    Collection<ManagedPlugin> getManagedPlugins();

    Optional<ManagedPlugin> findByName(String name);

    Optional<ManagedPlugin> findByPath(Path path);

    boolean reloadPlugin(String name) throws PluginLifecycleException;

    boolean reloadPlugin(Path path) throws PluginLifecycleException;

    boolean enablePlugin(String name) throws PluginLifecycleException;

    boolean disablePlugin(String name) throws PluginLifecycleException;

    boolean unloadPlugin(String name) throws PluginLifecycleException;

    boolean loadPlugin(Path path) throws PluginLifecycleException;

    java.util.Optional<ManagedPlugin> updateManagedPluginPath(Path oldPath, Path newPath);
}
