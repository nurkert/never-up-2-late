package eu.nurkert.neverUp2Late.plugin;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bukkit specific {@link ManagedPlugin} implementation that mirrors the
 * behaviour of the platform's plugin loader while providing deterministic
 * lifecycle operations.
 */
class BukkitManagedPlugin implements ManagedPlugin {

    private final PluginManager pluginManager;
    private final Logger logger;
    private final Path pluginPath;

    private Plugin plugin;
    private String lastKnownName;

    BukkitManagedPlugin(Plugin plugin, Path pluginPath, PluginManager pluginManager, Logger logger) {
        this.pluginManager = pluginManager;
        this.logger = logger;
        this.pluginPath = pluginPath.toAbsolutePath().normalize();
        attach(plugin);
        if (lastKnownName == null) {
            this.lastKnownName = deriveNameFromPath(pluginPath);
        }
    }

    @Override
    public synchronized void attach(Plugin plugin) {
        this.plugin = plugin;
        if (plugin != null) {
            this.lastKnownName = plugin.getName();
        }
    }

    @Override
    public synchronized String getName() {
        if (plugin != null) {
            return plugin.getName();
        }
        return lastKnownName != null ? lastKnownName : deriveNameFromPath(pluginPath);
    }

    @Override
    public Path getPath() {
        return pluginPath;
    }

    @Override
    public synchronized Optional<Plugin> getPlugin() {
        return Optional.ofNullable(plugin);
    }

    @Override
    public synchronized boolean isLoaded() {
        return plugin != null;
    }

    @Override
    public synchronized boolean isEnabled() {
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public synchronized void load() throws PluginLifecycleException {
        ensurePluginFileExists();
        if (plugin != null) {
            throw new PluginLifecycleException("Plugin " + getName() + " is already loaded");
        }
        try {
            Plugin loaded = pluginManager.loadPlugin(pluginPath.toFile());
            loaded.onLoad();
            attach(loaded);
        } catch (InvalidPluginException | InvalidDescriptionException ex) {
            throw new PluginLifecycleException("Failed to load plugin from " + pluginPath + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public synchronized void enable() throws PluginLifecycleException {
        if (plugin == null) {
            throw new PluginLifecycleException("Cannot enable plugin " + getName() + " because it is not loaded");
        }
        if (!plugin.isEnabled()) {
            pluginManager.enablePlugin(plugin);
        }
    }

    @Override
    public synchronized void disable() throws PluginLifecycleException {
        if (plugin == null) {
            return;
        }
        if (plugin.isEnabled()) {
            pluginManager.disablePlugin(plugin);
        }
    }

    @Override
    public synchronized void unload() throws PluginLifecycleException {
        if (plugin == null) {
            return;
        }

        Plugin existing = plugin;
        disable();
        removePluginFromBukkit(existing);
        detachClassLoader(existing);
        attach(null);
        System.gc();
    }

    @Override
    public synchronized void reload() throws PluginLifecycleException {
        unload();
        load();
        enable();
    }

    private void ensurePluginFileExists() throws PluginLifecycleException {
        if (!Files.exists(pluginPath)) {
            throw new PluginLifecycleException("Plugin file does not exist: " + pluginPath);
        }
    }

    private void removePluginFromBukkit(Plugin target) throws PluginLifecycleException {
        try {
            Field pluginsField = pluginManager.getClass().getDeclaredField("plugins");
            Field lookupNamesField = pluginManager.getClass().getDeclaredField("lookupNames");
            Field listenersField = null;
            try {
                listenersField = pluginManager.getClass().getDeclaredField("listeners");
            } catch (NoSuchFieldException ignored) {
                // Some Bukkit implementations removed this field; that's fine.
            }
            Field commandMapField = pluginManager.getClass().getDeclaredField("commandMap");

            pluginsField.setAccessible(true);
            lookupNamesField.setAccessible(true);
            commandMapField.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Plugin> plugins = (List<Plugin>) pluginsField.get(pluginManager);
            @SuppressWarnings("unchecked")
            Map<String, Plugin> names = (Map<String, Plugin>) lookupNamesField.get(pluginManager);

            if (plugins != null) {
                plugins.remove(target);
            }
            if (names != null) {
                names.values().removeIf(value -> value == target);
            }

            if (listenersField != null) {
                listenersField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Object, SortedSet<RegisteredListener>> listeners =
                        (Map<Object, SortedSet<RegisteredListener>>) listenersField.get(pluginManager);
                if (listeners != null) {
                    for (SortedSet<RegisteredListener> set : listeners.values()) {
                        if (set == null) {
                            continue;
                        }
                        Iterator<RegisteredListener> iterator = set.iterator();
                        while (iterator.hasNext()) {
                            RegisteredListener listener = iterator.next();
                            if (listener.getPlugin() == target) {
                                iterator.remove();
                            }
                        }
                    }
                }
            }

            SimpleCommandMap commandMap = (SimpleCommandMap) commandMapField.get(pluginManager);
            if (commandMap != null) {
                Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Object> knownCommands = (Map<String, Object>) knownCommandsField.get(commandMap);
                if (knownCommands != null) {
                    Iterator<Map.Entry<String, Object>> iterator = knownCommands.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<String, Object> entry = iterator.next();
                        Object value = entry.getValue();
                        if (value instanceof PluginCommand command && command.getPlugin() == target) {
                            command.unregister(commandMap);
                            iterator.remove();
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new PluginLifecycleException("Failed to cleanly unregister plugin " + getName(), ex);
        }
    }

    private void detachClassLoader(Plugin target) throws PluginLifecycleException {
        ClassLoader loader = target.getClass().getClassLoader();
        if (!(loader instanceof URLClassLoader urlClassLoader)) {
            return;
        }
        try {
            Field pluginField = loader.getClass().getDeclaredField("plugin");
            Field pluginInitField = loader.getClass().getDeclaredField("pluginInit");
            pluginField.setAccessible(true);
            pluginInitField.setAccessible(true);
            pluginField.set(loader, null);
            pluginInitField.set(loader, null);
        } catch (ReflectiveOperationException ex) {
            logger.log(Level.FINE, "Unable to detach plugin references from class loader", ex);
        }
        try {
            urlClassLoader.close();
        } catch (IOException ex) {
            throw new PluginLifecycleException("Failed to close class loader for " + getName(), ex);
        }
    }

    private String deriveNameFromPath(Path path) {
        String fileName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        if (fileName.endsWith(".jar")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }
}
