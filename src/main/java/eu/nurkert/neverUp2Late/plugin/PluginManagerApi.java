package eu.nurkert.neverUp2Late.plugin;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link PluginLifecycleManager}. It exposes a
 * uniform API for managing Bukkit plugins via reflective access to the
 * platform's internals when necessary.
 */
public class PluginManagerApi implements PluginLifecycleManager {

    private final PluginManager pluginManager;
    private final Path pluginsDirectory;
    private final Logger logger;
    private final Map<Path, ManagedPlugin> managedPlugins = new ConcurrentHashMap<>();

    public PluginManagerApi(PluginManager pluginManager, File pluginsDirectory, Logger logger) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.pluginsDirectory = pluginsDirectory == null
                ? null
                : pluginsDirectory.toPath().toAbsolutePath().normalize();
        this.logger = logger;
    }

    @Override
    public void registerPlugin(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        resolvePluginPath(plugin).ifPresent(path -> {
            ManagedPlugin managed = managedPlugins.get(path);
            if (managed != null) {
                managed.attach(plugin);
            } else {
                managedPlugins.put(path, new BukkitManagedPlugin(plugin, path, pluginManager, logger));
            }
        });
    }

    @Override
    public void registerLoadedPlugins(Plugin self) {
        for (Plugin plugin : pluginManager.getPlugins()) {
            if (plugin == null || plugin.equals(self)) {
                continue;
            }
            registerPlugin(plugin);
        }
    }

    @Override
    public Collection<ManagedPlugin> getManagedPlugins() {
        return Collections.unmodifiableCollection(managedPlugins.values());
    }

    @Override
    public Optional<ManagedPlugin> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim();
        return managedPlugins.values().stream()
                .filter(plugin -> {
                    String candidate = plugin.getName();
                    return candidate != null && candidate.equalsIgnoreCase(normalized);
                })
                .findFirst();
    }

    @Override
    public Optional<ManagedPlugin> findByPath(Path path) {
        if (path == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(managedPlugins.get(normalize(path)));
    }

    @Override
    public boolean reloadPlugin(String name) throws PluginLifecycleException {
        Optional<ManagedPlugin> plugin = findByName(name);
        if (plugin.isEmpty()) {
            return false;
        }
        plugin.get().reload();
        return true;
    }

    @Override
    public boolean reloadPlugin(Path path) throws PluginLifecycleException {
        ManagedPlugin managed = ensureManaged(path);
        if (managed == null) {
            return false;
        }
        managed.reload();
        return true;
    }

    @Override
    public boolean enablePlugin(String name) throws PluginLifecycleException {
        Optional<ManagedPlugin> plugin = findByName(name);
        if (plugin.isEmpty()) {
            return false;
        }
        plugin.get().enable();
        return true;
    }

    @Override
    public boolean disablePlugin(String name) throws PluginLifecycleException {
        Optional<ManagedPlugin> plugin = findByName(name);
        if (plugin.isEmpty()) {
            return false;
        }
        plugin.get().disable();
        return true;
    }

    @Override
    public boolean unloadPlugin(String name) throws PluginLifecycleException {
        Optional<ManagedPlugin> plugin = findByName(name);
        if (plugin.isEmpty()) {
            return false;
        }
        plugin.get().unload();
        return true;
    }

    @Override
    public boolean loadPlugin(Path path) throws PluginLifecycleException {
        ManagedPlugin managed = ensureManaged(path);
        if (managed == null) {
            return false;
        }
        if (managed.isLoaded()) {
            return false;
        }
        managed.load();
        managed.enable();
        return true;
    }

    @Override
    public void deleteAllDuplicates(String pluginName, Path preferredPath) {
        if (pluginName == null || pluginName.isBlank() || pluginsDirectory == null) {
            return;
        }

        List<ArchiveUtils.PluginInfo> jars = ArchiveUtils.findJarsForPlugin(pluginsDirectory, pluginName);
        if (jars.isEmpty()) {
            return;
        }

        Path keepPath;
        if (preferredPath != null) {
            keepPath = preferredPath.toAbsolutePath().normalize();
        } else {
            // Cleanup mode: find highest version
            keepPath = ArchiveUtils.findBestJar(jars).map(ArchiveUtils.PluginInfo::path).orElse(null);
        }

        if (keepPath == null) {
            return;
        }

        for (ArchiveUtils.PluginInfo jar : jars) {
            Path jarPath = jar.path().toAbsolutePath().normalize();
            if (jarPath.equals(keepPath)) {
                continue;
            }

            try {
                Files.delete(jarPath);
                logger.log(Level.INFO, "Deleted old/duplicate JAR for plugin {0}: {1} (Version {2})",
                        new Object[]{pluginName, jarPath.getFileName(), jar.version()});
                managedPlugins.remove(jarPath);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to delete duplicate JAR: " + jarPath, e);
            }
        }
    }

    @Override
    public Optional<ManagedPlugin> updateManagedPluginPath(Path oldPath, Path newPath) {
        if (oldPath == null || newPath == null) {
            return Optional.empty();
        }
        ManagedPlugin existing = managedPlugins.remove(normalize(oldPath));
        if (existing == null) {
            return Optional.empty();
        }
        ManagedPlugin replacement = new BukkitManagedPlugin(existing.getPlugin().orElse(null), newPath, pluginManager, logger);
        managedPlugins.put(normalize(newPath), replacement);
        return Optional.of(replacement);
    }

    private ManagedPlugin ensureManaged(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = normalize(path);
        ManagedPlugin managed = managedPlugins.get(normalized);
        if (managed != null) {
            return managed;
        }
        if (!Files.exists(normalized)) {
            return null;
        }
        ManagedPlugin newManaged = new BukkitManagedPlugin(null, normalized, pluginManager, logger);
        managedPlugins.put(normalized, newManaged);
        return newManaged;
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private Optional<Path> resolvePluginPath(Plugin plugin) {
        Path fromCodeSource = resolveFromCodeSource(plugin);
        if (fromCodeSource != null) {
            return Optional.of(fromCodeSource);
        }
        
        // Fallback: Scan directory
        if (pluginsDirectory == null) {
            return Optional.empty();
        }
        
        String unifiedName = unify(plugin.getName());
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDirectory, "*.jar")) {
            for (Path entry : stream) {
                String entryUnified = unify(entry.getFileName().toString());
                if (entryUnified.equals(unifiedName) || entryUnified.equals(unifiedName + "jar")) {
                    return Optional.of(entry.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ex) {
            logFine("Failed to scan plugins directory for " + plugin.getName(), ex);
        }
        return Optional.empty();
    }

    private Path resolveFromCodeSource(Plugin plugin) {
        try {
            CodeSource codeSource = plugin.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return null;
            }
            if (codeSource.getLocation() == null) {
                return null;
            }
            Path location = Path.of(codeSource.getLocation().toURI());
            if (Files.isRegularFile(location)) {
                return location.toAbsolutePath().normalize();
            }
        } catch (Exception ex) {
            logFine("Unable to resolve plugin path from code source for " + plugin.getName(), ex);
        }
        return null;
    }

    private String unify(String value) {
        if (value == null) {
            return "";
        }
        String unified = value.toLowerCase(Locale.ROOT);
        unified = unified.replace(" ", "");
        unified = unified.replace("-", "");
        unified = unified.replace("_", "");
        unified = unified.replace(".", "");
        return unified;
    }

    private void logFine(String message, Exception ex) {
        if (logger != null) {
            logger.log(Level.FINE, message, ex);
        }
    }
}
