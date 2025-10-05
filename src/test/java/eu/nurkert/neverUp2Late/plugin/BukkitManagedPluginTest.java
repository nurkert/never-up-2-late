package eu.nurkert.neverUp2Late.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.UnknownDependencyException;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitManagedPluginTest {

    @Test
    void loadInvokesOnLoadOnlyOnce() throws Exception {
        Path pluginFile = Files.createTempFile("plugin", ".jar");
        try {
            CountingPlugin plugin = new CountingPlugin("TestPlugin");
            CountingPluginManager manager = new CountingPluginManager(plugin, pluginFile);
            Logger logger = Logger.getLogger("BukkitManagedPluginTest");

            BukkitManagedPlugin managed = new BukkitManagedPlugin(null, pluginFile, manager, logger);

            managed.load();

            assertEquals(1, plugin.getOnLoadCalls());
        } finally {
            Files.deleteIfExists(pluginFile);
        }
    }

    @Test
    void unloadRemovesPluginReferencesStoredInSuperclass() throws Exception {
        Path pluginFile = Files.createTempFile("hierarchy-plugin", ".jar");
        try {
            StatefulPlugin plugin = new StatefulPlugin("HierarchyPlugin");
            SuperclassBackedPluginManager manager = new SuperclassBackedPluginManager(plugin, pluginFile);
            Logger logger = Logger.getLogger("BukkitManagedPluginTest");

            BukkitManagedPlugin managed = new BukkitManagedPlugin(null, pluginFile, manager, logger);

            managed.load();
            managed.enable();

            assertTrue(manager.isPluginRegistered(plugin));
            assertTrue(manager.isNameRegistered(plugin.getName()));

            managed.unload();

            assertFalse(manager.isPluginRegistered(plugin));
            assertFalse(manager.isNameRegistered(plugin.getName()));
            assertFalse(plugin.isEnabled());
        } finally {
            Files.deleteIfExists(pluginFile);
        }
    }

    @Test
    void unloadReplacesUnmodifiableKnownCommandsMap() throws Exception {
        Path pluginFile = Files.createTempFile("command-plugin", ".jar");
        try {
            StatefulPlugin plugin = new StatefulPlugin("CommandPlugin");
            SuperclassBackedPluginManager manager = new SuperclassBackedPluginManager(plugin, pluginFile);
            Logger logger = Logger.getLogger("BukkitManagedPluginTest");

            PluginCommand command = createPluginCommand("command:test", plugin);
            Map<String, Object> backing = new HashMap<>();
            backing.put(command.getName(), command);
            CommandMapHolder holder = new CommandMapHolder(Collections.unmodifiableMap(backing));
            manager.commandMap = holder;

            BukkitManagedPlugin managed = new BukkitManagedPlugin(null, pluginFile, manager, logger);

            managed.load();
            managed.enable();

            assertTrue(holder.knownCommands().containsKey(command.getName()));

            managed.unload();

            Map<String, Object> knownCommands = holder.knownCommands();
            assertTrue(knownCommands instanceof LinkedHashMap);
            assertFalse(knownCommands.containsKey(command.getName()));
        } finally {
            Files.deleteIfExists(pluginFile);
        }
    }

    private static PluginCommand createPluginCommand(String name, Plugin owner) throws Exception {
        Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, owner);
    }

    private static final class CommandMapHolder {

        private Map<String, Object> knownCommands;

        private CommandMapHolder(Map<String, Object> knownCommands) {
            this.knownCommands = knownCommands;
        }

        Map<String, Object> knownCommands() {
            return knownCommands;
        }
    }

    private static final class CountingPluginManager implements PluginManager {

        private final Plugin plugin;
        private final Path expectedPath;

        private CountingPluginManager(Plugin plugin, Path expectedPath) {
            this.plugin = plugin;
            this.expectedPath = expectedPath;
        }

        @Override
        public Plugin loadPlugin(File file) throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
            if (!expectedPath.toFile().equals(file)) {
                throw new InvalidPluginException("Unexpected plugin file");
            }
            plugin.onLoad();
            return plugin;
        }

        @Override
        public void registerInterface(Class<? extends PluginLoader> loader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Plugin getPlugin(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Plugin[] getPlugins() {
            return new Plugin[0];
        }

        @Override
        public boolean isPluginEnabled(String name) {
            return false;
        }

        @Override
        public boolean isPluginEnabled(Plugin plugin) {
            return false;
        }

        @Override
        public Plugin[] loadPlugins(File directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disablePlugins() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearPlugins() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void callEvent(Event event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerEvents(Listener listener, Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin, boolean ignoreCancelled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void enablePlugin(Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disablePlugin(Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Permission getPermission(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addPermission(Permission perm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removePermission(Permission perm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removePermission(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Permission> getDefaultPermissions(boolean op) {
            return Collections.emptySet();
        }

        @Override
        public void recalculatePermissionDefaults(Permission perm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribeToPermission(String permission, Permissible permissible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unsubscribeFromPermission(String permission, Permissible permissible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Permissible> getPermissionSubscriptions(String permission) {
            return Collections.emptySet();
        }

        @Override
        public void subscribeToDefaultPerms(boolean op, Permissible permissible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unsubscribeFromDefaultPerms(boolean op, Permissible permissible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Permissible> getDefaultPermSubscriptions(boolean op) {
            return Collections.emptySet();
        }

        @Override
        public Set<Permission> getPermissions() {
            return Collections.emptySet();
        }

        @Override
        public boolean useTimings() {
            return false;
        }
    }

    private static final class SuperclassBackedPluginManager implements PluginManager {

        private final StatefulPlugin plugin;
        private final Path expectedPath;
        private boolean enabled;

        protected final List<Plugin> plugins = new ArrayList<>();
        protected final Map<String, Plugin> lookupNames = new HashMap<>();
        protected final Map<Object, SortedSet<RegisteredListener>> listeners = new HashMap<>();
        @SuppressWarnings("unused")
        protected Object commandMap;

        private SuperclassBackedPluginManager(StatefulPlugin plugin, Path expectedPath) {
            this.plugin = plugin;
            this.expectedPath = expectedPath;
        }

        @Override
        public Plugin loadPlugin(File file) throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
            if (!expectedPath.toFile().equals(file)) {
                throw new InvalidPluginException("Unexpected plugin file");
            }
            plugin.onLoad();
            plugins.add(plugin);
            lookupNames.put(plugin.getName(), plugin);
            enabled = false;
            return plugin;
        }

        @Override
        public Plugin[] getPlugins() {
            return plugins.toArray(new Plugin[0]);
        }

        @Override
        public Plugin getPlugin(String name) {
            return lookupNames.get(name);
        }

        @Override
        public boolean isPluginEnabled(String name) {
            return enabled && lookupNames.containsKey(name);
        }

        @Override
        public boolean isPluginEnabled(Plugin plugin) {
            return enabled && plugins.contains(plugin);
        }

        @Override
        public void enablePlugin(Plugin plugin) {
            if (plugin != this.plugin) {
                throw new UnsupportedOperationException();
            }
            enabled = true;
            this.plugin.onEnable();
        }

        @Override
        public void disablePlugin(Plugin plugin) {
            if (plugin != this.plugin) {
                throw new UnsupportedOperationException();
            }
            enabled = false;
            this.plugin.onDisable();
        }

        boolean isPluginRegistered(Plugin plugin) {
            return plugins.contains(plugin);
        }

        boolean isNameRegistered(String name) {
            return lookupNames.containsKey(name);
        }

        @Override
        public void registerInterface(Class<? extends PluginLoader> loader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Plugin[] loadPlugins(File directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disablePlugins() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearPlugins() {
            plugins.clear();
            lookupNames.clear();
        }

        @Override
        public void callEvent(Event event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerEvents(Listener listener, Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin, boolean ignoreCancelled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Permission getPermission(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addPermission(Permission perm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removePermission(Permission perm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removePermission(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Permission> getDefaultPermissions(boolean op) {
            return Collections.emptySet();
        }

        @Override
        public void recalculatePermissionDefaults(Permission perm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribeToPermission(String permission, Permissible permissible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unsubscribeFromPermission(String permission, Permissible permissible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Permissible> getPermissionSubscriptions(String permission) {
            return Collections.emptySet();
        }

        @Override
        public void subscribeToDefaultPerms(boolean op, Permissible permissible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unsubscribeFromDefaultPerms(boolean op, Permissible permissible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Permissible> getDefaultPermSubscriptions(boolean op) {
            return Collections.emptySet();
        }

        @Override
        public Set<Permission> getPermissions() {
            return Collections.emptySet();
        }

        @Override
        public boolean useTimings() {
            return false;
        }
    }

    private static final class CountingPlugin implements Plugin {

        private final String name;
        private int onLoadCalls = 0;
        private boolean naggable = true;

        private CountingPlugin(String name) {
            this.name = name;
        }

        @Override
        public File getDataFolder() {
            return new File(".");
        }

        @Override
        public PluginDescriptionFile getDescription() {
            return null;
        }

        @Override
        public FileConfiguration getConfig() {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream getResource(String name) {
            return null;
        }

        @Override
        public void saveConfig() {
        }

        @Override
        public void saveDefaultConfig() {
        }

        @Override
        public void saveResource(String resourcePath, boolean replace) {
        }

        @Override
        public void reloadConfig() {
        }

        @Override
        public PluginLoader getPluginLoader() {
            return null;
        }

        @Override
        public Server getServer() {
            return null;
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void onDisable() {
        }

        @Override
        public void onLoad() {
            onLoadCalls++;
        }

        @Override
        public void onEnable() {
        }

        @Override
        public boolean isNaggable() {
            return naggable;
        }

        @Override
        public void setNaggable(boolean canNag) {
            this.naggable = canNag;
        }

        @Override
        public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
            return null;
        }

        @Override
        public BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
            return null;
        }

        @Override
        public Logger getLogger() {
            return Logger.getLogger("CountingPlugin");
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            return false;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }

        int getOnLoadCalls() {
            return onLoadCalls;
        }
    }

    private static final class StatefulPlugin implements Plugin {

        private final String name;
        private boolean enabled;

        private StatefulPlugin(String name) {
            this.name = name;
        }

        @Override
        public File getDataFolder() {
            return new File(".");
        }

        @Override
        public PluginDescriptionFile getDescription() {
            return null;
        }

        @Override
        public FileConfiguration getConfig() {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream getResource(String name) {
            return null;
        }

        @Override
        public void saveConfig() {
        }

        @Override
        public void saveDefaultConfig() {
        }

        @Override
        public void saveResource(String resourcePath, boolean replace) {
        }

        @Override
        public void reloadConfig() {
        }

        @Override
        public PluginLoader getPluginLoader() {
            return null;
        }

        @Override
        public Server getServer() {
            return null;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void onDisable() {
            enabled = false;
        }

        @Override
        public void onLoad() {
        }

        @Override
        public void onEnable() {
            enabled = true;
        }

        @Override
        public boolean isNaggable() {
            return true;
        }

        @Override
        public void setNaggable(boolean canNag) {
        }

        @Override
        public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
            return null;
        }

        @Override
        public BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
            return null;
        }

        @Override
        public Logger getLogger() {
            return Logger.getLogger("StatefulPlugin");
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            return false;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }
}
