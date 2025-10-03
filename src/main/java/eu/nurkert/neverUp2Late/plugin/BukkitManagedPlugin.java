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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
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
            Class<?> managerClass = pluginManager.getClass();

            List<Plugin> plugins = extractPlugins(pluginManager);
            if (plugins != null) {
                plugins.removeIf(candidate -> candidate == target);
            }

            Map<String, Plugin> names = extractLookupNames(pluginManager);
            if (names != null) {
                names.values().removeIf(value -> value == target);
            }

            Map<Object, SortedSet<RegisteredListener>> listeners = extractRegisteredListeners(pluginManager);
            if (listeners != null) {
                for (Map.Entry<Object, SortedSet<RegisteredListener>> entry : listeners.entrySet()) {
                    SortedSet<RegisteredListener> set = entry.getValue();
                    if (set == null || set.isEmpty()) {
                        continue;
                    }
                    try {
                        set.removeIf(listener -> listener.getPlugin() == target);
                    } catch (UnsupportedOperationException ex) {
                        Comparator<? super RegisteredListener> comparator = set.comparator();
                        SortedSet<RegisteredListener> mutable = new TreeSet<>(comparator);
                        for (RegisteredListener listener : set) {
                            if (listener.getPlugin() != target) {
                                mutable.add(listener);
                            }
                        }
                        listeners.put(entry.getKey(), mutable);
                    }
                }
            }

            Object commandMap = extractCommandMap(pluginManager);
            if (commandMap != null) {
                Optional<Field> knownCommandsField = findFieldInHierarchy(commandMap.getClass(), "knownCommands");
                if (knownCommandsField.isPresent()) {
                    Field field = knownCommandsField.get();
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> knownCommands = (Map<String, Object>) field.get(commandMap);
                    if (knownCommands != null) {
                        Iterator<Map.Entry<String, Object>> iterator = knownCommands.entrySet().iterator();
                        while (iterator.hasNext()) {
                            Map.Entry<String, Object> entry = iterator.next();
                            Object value = entry.getValue();
                            if (value instanceof PluginCommand command && command.getPlugin() == target) {
                                if (commandMap instanceof SimpleCommandMap simple) {
                                    command.unregister(simple);
                                }
                                iterator.remove();
                            }
                        }
                    }
                } else {
                    logger.log(Level.FINE, () -> "Command map " + commandMap.getClass().getName()
                            + " does not expose 'knownCommands'; skipping command cleanup for " + getName());
                }
            }
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new PluginLifecycleException("Failed to cleanly unregister plugin " + getName(), ex);
        }
    }

    private List<Plugin> extractPlugins(Object manager) throws IllegalAccessException {
        return extractPlugins(manager, newIdentitySet());
    }

    private List<Plugin> extractPlugins(Object manager, Set<Object> visited) throws IllegalAccessException {
        if (manager == null || visited.contains(manager)) {
            return null;
        }
        visited.add(manager);

        Optional<Field> pluginsField = findFieldInHierarchy(manager.getClass(), "plugins");
        if (pluginsField.isPresent()) {
            Field field = pluginsField.get();
            field.setAccessible(true);
            Object value = field.get(manager);
            if (value instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Plugin> plugins = (List<Plugin>) list;
                return plugins;
            }
            logger.log(Level.FINE, () -> "Unexpected type for 'plugins' field on " + manager.getClass().getName());
        }

        Optional<Field> instanceManagerField = findFieldInHierarchy(manager.getClass(), "instanceManager");
        if (instanceManagerField.isPresent()) {
            Field delegateField = instanceManagerField.get();
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(manager);
            List<Plugin> result = extractPlugins(delegate, visited);
            if (result != null) {
                return result;
            }
        }

        logger.log(Level.FINE, () -> "Unable to locate plugin list on " + manager.getClass().getName());
        return null;
    }

    private Map<String, Plugin> extractLookupNames(Object manager) throws IllegalAccessException {
        return extractLookupNames(manager, newIdentitySet());
    }

    private Map<String, Plugin> extractLookupNames(Object manager, Set<Object> visited) throws IllegalAccessException {
        if (manager == null || visited.contains(manager)) {
            return null;
        }
        visited.add(manager);

        Optional<Field> lookupNamesField = findFieldInHierarchy(manager.getClass(), "lookupNames");
        if (lookupNamesField.isPresent()) {
            Field field = lookupNamesField.get();
            field.setAccessible(true);
            Object value = field.get(manager);
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Plugin> names = (Map<String, Plugin>) map;
                return names;
            }
            logger.log(Level.FINE, () -> "Unexpected type for 'lookupNames' field on " + manager.getClass().getName());
        }

        Optional<Field> instanceManagerField = findFieldInHierarchy(manager.getClass(), "instanceManager");
        if (instanceManagerField.isPresent()) {
            Field delegateField = instanceManagerField.get();
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(manager);
            Map<String, Plugin> result = extractLookupNames(delegate, visited);
            if (result != null) {
                return result;
            }
        }

        logger.log(Level.FINE, () -> "Unable to locate plugin lookup names on " + manager.getClass().getName());
        return null;
    }

    private Map<Object, SortedSet<RegisteredListener>> extractRegisteredListeners(Object manager)
            throws IllegalAccessException {
        return extractRegisteredListeners(manager, newIdentitySet());
    }

    private Map<Object, SortedSet<RegisteredListener>> extractRegisteredListeners(Object manager, Set<Object> visited)
            throws IllegalAccessException {
        if (manager == null || visited.contains(manager)) {
            return null;
        }
        visited.add(manager);

        Optional<Field> listenersField = findFieldInHierarchy(manager.getClass(), "listeners");
        if (listenersField.isPresent()) {
            Field field = listenersField.get();
            field.setAccessible(true);
            Object value = field.get(manager);
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<Object, SortedSet<RegisteredListener>> listeners =
                        (Map<Object, SortedSet<RegisteredListener>>) map;
                return listeners;
            }
            logger.log(Level.FINE, () -> "Unexpected type for 'listeners' field on " + manager.getClass().getName());
        }

        for (String delegateFieldName : new String[] {"paperEventManager", "eventManager"}) {
            Optional<Field> delegateField = findFieldInHierarchy(manager.getClass(), delegateFieldName);
            if (delegateField.isPresent()) {
                Field field = delegateField.get();
                field.setAccessible(true);
                Object delegate = field.get(manager);
                Map<Object, SortedSet<RegisteredListener>> result = extractRegisteredListeners(delegate, visited);
                if (result != null) {
                    return result;
                }
            }
        }

        logger.log(Level.FINEST, () -> "Unable to locate registered listeners on " + manager.getClass().getName());
        return null;
    }

    private Object extractCommandMap(Object manager)
            throws IllegalAccessException, InvocationTargetException {
        return extractCommandMap(manager, newIdentitySet());
    }

    private Object extractCommandMap(Object manager, Set<Object> visited)
            throws IllegalAccessException, InvocationTargetException {
        if (manager == null || visited.contains(manager)) {
            return null;
        }
        visited.add(manager);

        Optional<Field> commandMapField = findFieldInHierarchy(manager.getClass(), "commandMap");
        if (commandMapField.isPresent()) {
            Field field = commandMapField.get();
            field.setAccessible(true);
            return field.get(manager);
        }

        Optional<Method> accessor = findMethodInHierarchy(manager.getClass(), "getCommandMap");
        if (accessor.isPresent()) {
            Method method = accessor.get();
            method.setAccessible(true);
            Object result = method.invoke(manager);
            if (result != null) {
                return result;
            }
        }

        Optional<Field> instanceManagerField = findFieldInHierarchy(manager.getClass(), "instanceManager");
        if (instanceManagerField.isPresent()) {
            Field delegateField = instanceManagerField.get();
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(manager);
            Object result = extractCommandMap(delegate, visited);
            if (result != null) {
                return result;
            }
        }

        logger.log(Level.FINE, () -> "Unable to locate command map on " + manager.getClass().getName());
        return null;
    }

    private Optional<Field> findFieldInHierarchy(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return Optional.of(current.getDeclaredField(fieldName));
            } catch (NoSuchFieldException ex) {
                current = current.getSuperclass();
            }
        }
        return Optional.empty();
    }

    private Optional<Method> findMethodInHierarchy(Class<?> type, String methodName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return Optional.of(current.getDeclaredMethod(methodName));
            } catch (NoSuchMethodException ex) {
                current = current.getSuperclass();
            }
        }
        return Optional.empty();
    }

    private Set<Object> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
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
