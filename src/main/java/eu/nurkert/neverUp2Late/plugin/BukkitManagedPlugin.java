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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
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
            FieldAccess<List<Plugin>> pluginsAccess = extractPlugins(pluginManager);
            if (pluginsAccess != null) {
                removePluginFromList(pluginsAccess, target);
            }

            FieldAccess<Map<String, Plugin>> lookupNamesAccess = extractLookupNames(pluginManager);
            if (lookupNamesAccess != null) {
                removePluginFromMap(lookupNamesAccess, entry -> entry.getValue() == target);
            }

            FieldAccess<Map<Object, SortedSet<RegisteredListener>>> listenersAccess = extractRegisteredListeners(pluginManager);
            if (listenersAccess != null) {
                cleanupRegisteredListeners(listenersAccess, target);
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
                        boolean modified = false;
                        try {
                            Iterator<Map.Entry<String, Object>> iterator = knownCommands.entrySet().iterator();
                            while (iterator.hasNext()) {
                                Map.Entry<String, Object> entry = iterator.next();
                                Object value = entry.getValue();
                                if (value instanceof PluginCommand command && command.getPlugin() == target) {
                                    if (commandMap instanceof SimpleCommandMap simple) {
                                        command.unregister(simple);
                                    }
                                    iterator.remove();
                                    modified = true;
                                }
                            }
                        } catch (UnsupportedOperationException ex) {
                            Map<String, Object> mutable = new LinkedHashMap<>(knownCommands.size());
                            for (Map.Entry<String, Object> entry : knownCommands.entrySet()) {
                                Object value = entry.getValue();
                                if (value instanceof PluginCommand command && command.getPlugin() == target) {
                                    if (commandMap instanceof SimpleCommandMap simple) {
                                        command.unregister(simple);
                                    }
                                    modified = true;
                                    continue;
                                }
                                mutable.put(entry.getKey(), value);
                            }
                            if (modified) {
                                field.set(commandMap, mutable);
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

    private void removePluginFromList(FieldAccess<List<Plugin>> access, Plugin target) throws IllegalAccessException {
        List<Plugin> plugins = access.value();
        boolean removed = false;
        try {
            removed = plugins.removeIf(candidate -> candidate == target);
        } catch (UnsupportedOperationException ex) {
            // fall through to reflective replacement
        }
        if (removed) {
            return;
        }
        List<Plugin> mutable = new ArrayList<>(plugins.size());
        boolean changed = false;
        for (Plugin plugin : plugins) {
            if (plugin == target) {
                changed = true;
            } else {
                mutable.add(plugin);
            }
        }
        if (changed) {
            access.field().set(access.owner(), mutable);
        }
    }

    private <K, V> void removePluginFromMap(FieldAccess<Map<K, V>> access,
            Predicate<Map.Entry<K, V>> shouldRemove) throws IllegalAccessException {
        Map<K, V> map = access.value();
        boolean modified = false;
        try {
            Iterator<Map.Entry<K, V>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<K, V> entry = iterator.next();
                if (shouldRemove.test(entry)) {
                    iterator.remove();
                    modified = true;
                }
            }
        } catch (UnsupportedOperationException ex) {
            // fall through to reflective replacement
        }
        if (modified) {
            return;
        }
        Map<K, V> mutable = new LinkedHashMap<>(map.size());
        boolean changed = false;
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (shouldRemove.test(entry)) {
                changed = true;
                continue;
            }
            mutable.put(entry.getKey(), entry.getValue());
        }
        if (changed) {
            access.field().set(access.owner(), mutable);
        }
    }

    private void cleanupRegisteredListeners(FieldAccess<Map<Object, SortedSet<RegisteredListener>>> access, Plugin target)
            throws IllegalAccessException {
        Map<Object, SortedSet<RegisteredListener>> listeners = access.value();
        Map<Object, SortedSet<RegisteredListener>> mutable = new LinkedHashMap<>(listeners.size());
        boolean changed = false;
        for (Map.Entry<Object, SortedSet<RegisteredListener>> entry : listeners.entrySet()) {
            SortedSet<RegisteredListener> filtered = filterRegisteredListeners(entry.getValue(), target);
            if (filtered != entry.getValue()) {
                changed = true;
            }
            mutable.put(entry.getKey(), filtered);
        }
        if (!changed) {
            return;
        }
        try {
            listeners.clear();
            listeners.putAll(mutable);
        } catch (UnsupportedOperationException ex) {
            access.field().set(access.owner(), mutable);
        }
    }

    private SortedSet<RegisteredListener> filterRegisteredListeners(SortedSet<RegisteredListener> listeners,
            Plugin target) {
        if (listeners == null || listeners.isEmpty()) {
            return listeners;
        }
        Comparator<? super RegisteredListener> comparator = listeners.comparator();
        SortedSet<RegisteredListener> mutable = new TreeSet<>(comparator);
        boolean changed = false;
        for (RegisteredListener listener : listeners) {
            if (listener.getPlugin() == target) {
                changed = true;
                continue;
            }
            mutable.add(listener);
        }
        return changed ? mutable : listeners;
    }

    private FieldAccess<List<Plugin>> extractPlugins(Object manager) throws IllegalAccessException {
        return extractPlugins(manager, newIdentitySet());
    }

    private FieldAccess<List<Plugin>> extractPlugins(Object manager, Set<Object> visited) throws IllegalAccessException {
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
                return new FieldAccess<>(manager, field, plugins);
            }
            logger.log(Level.FINE, () -> "Unexpected type for 'plugins' field on " + manager.getClass().getName());
        }

        Optional<Field> instanceManagerField = findFieldInHierarchy(manager.getClass(), "instanceManager");
        if (instanceManagerField.isPresent()) {
            Field delegateField = instanceManagerField.get();
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(manager);
            FieldAccess<List<Plugin>> result = extractPlugins(delegate, visited);
            if (result != null) {
                return result;
            }
        }

        logger.log(Level.FINE, () -> "Unable to locate plugin list on " + manager.getClass().getName());
        return null;
    }

    private FieldAccess<Map<String, Plugin>> extractLookupNames(Object manager) throws IllegalAccessException {
        return extractLookupNames(manager, newIdentitySet());
    }

    private FieldAccess<Map<String, Plugin>> extractLookupNames(Object manager, Set<Object> visited) throws IllegalAccessException {
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
                return new FieldAccess<>(manager, field, names);
            }
            logger.log(Level.FINE, () -> "Unexpected type for 'lookupNames' field on " + manager.getClass().getName());
        }

        Optional<Field> instanceManagerField = findFieldInHierarchy(manager.getClass(), "instanceManager");
        if (instanceManagerField.isPresent()) {
            Field delegateField = instanceManagerField.get();
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(manager);
            FieldAccess<Map<String, Plugin>> result = extractLookupNames(delegate, visited);
            if (result != null) {
                return result;
            }
        }

        logger.log(Level.FINE, () -> "Unable to locate plugin lookup names on " + manager.getClass().getName());
        return null;
    }

    private FieldAccess<Map<Object, SortedSet<RegisteredListener>>> extractRegisteredListeners(Object manager)
            throws IllegalAccessException {
        return extractRegisteredListeners(manager, newIdentitySet());
    }

    private FieldAccess<Map<Object, SortedSet<RegisteredListener>>> extractRegisteredListeners(Object manager, Set<Object> visited)
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
                return new FieldAccess<>(manager, field, listeners);
            }
            logger.log(Level.FINE, () -> "Unexpected type for 'listeners' field on " + manager.getClass().getName());
        }

        for (String delegateFieldName : new String[] {"paperEventManager", "eventManager"}) {
            Optional<Field> delegateField = findFieldInHierarchy(manager.getClass(), delegateFieldName);
            if (delegateField.isPresent()) {
                Field field = delegateField.get();
                field.setAccessible(true);
                Object delegate = field.get(manager);
                FieldAccess<Map<Object, SortedSet<RegisteredListener>>> result = extractRegisteredListeners(delegate, visited);
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

    private static final class FieldAccess<T> {
        private final Object owner;
        private final Field field;
        private final T value;

        FieldAccess(Object owner, Field field, T value) {
            this.owner = owner;
            this.field = field;
            this.value = value;
        }

        Object owner() {
            return owner;
        }

        Field field() {
            return field;
        }

        T value() {
            return value;
        }
    }
}
