package eu.nurkert.neverUp2Late.core;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.persistence.SetupStateRepository;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository.PluginState;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry for plugin level services and shared Bukkit infrastructure components.
 */
public class PluginContext {

    private final JavaPlugin plugin;
    private final BukkitScheduler scheduler;
    private final FileConfiguration configuration;

    private final PersistentPluginHandler persistentPluginHandler;
    private final UpdateHandler updateHandler;
    private final InstallationHandler installationHandler;
    private final UpdateSourceRegistry updateSourceRegistry;
    private final PluginLifecycleManager pluginLifecycleManager;
    private final PluginUpdateSettingsRepository pluginUpdateSettingsRepository;
    private final SetupStateRepository setupStateRepository;
    private final ArtifactDownloader artifactDownloader;

    public PluginContext(JavaPlugin plugin,
                         BukkitScheduler scheduler,
                         FileConfiguration configuration,
                         PersistentPluginHandler persistentPluginHandler,
                         UpdateHandler updateHandler,
                         InstallationHandler installationHandler,
                         UpdateSourceRegistry updateSourceRegistry,
                         PluginLifecycleManager pluginLifecycleManager,
                         PluginUpdateSettingsRepository pluginUpdateSettingsRepository,
                         SetupStateRepository setupStateRepository,
                         ArtifactDownloader artifactDownloader) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.persistentPluginHandler = persistentPluginHandler;
        this.updateHandler = updateHandler;
        this.installationHandler = installationHandler;
        this.updateSourceRegistry = updateSourceRegistry;
        this.pluginLifecycleManager = pluginLifecycleManager;
        this.pluginUpdateSettingsRepository = pluginUpdateSettingsRepository;
        this.setupStateRepository = setupStateRepository;
        this.artifactDownloader = artifactDownloader;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public BukkitScheduler getScheduler() {
        return scheduler;
    }

    public FileConfiguration getConfiguration() {
        return configuration;
    }

    public PersistentPluginHandler getPersistentPluginHandler() {
        return persistentPluginHandler;
    }

    public UpdateHandler getUpdateHandler() {
        return updateHandler;
    }

    public InstallationHandler getInstallationHandler() {
        return installationHandler;
    }

    public UpdateSourceRegistry getUpdateSourceRegistry() {
        return updateSourceRegistry;
    }

    public PluginLifecycleManager getPluginLifecycleManager() {
        return pluginLifecycleManager;
    }

    public PluginUpdateSettingsRepository getPluginUpdateSettingsRepository() {
        return pluginUpdateSettingsRepository;
    }

    public SetupStateRepository getSetupStateRepository() {
        return setupStateRepository;
    }

    public ArtifactDownloader getArtifactDownloader() {
        return artifactDownloader;
    }

    public List<UpdateSourceStatus> getUpdateSourceStatuses() {
        if (updateSourceRegistry == null) {
            return Collections.emptyList();
        }

        List<UpdateSource> sources = updateSourceRegistry.getSources();
        if (sources.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, PluginState> storedStates = Collections.emptyMap();
        if (persistentPluginHandler != null) {
            List<String> names = new ArrayList<>(sources.size());
            for (UpdateSource source : sources) {
                if (source == null) {
                    continue;
                }
                String name = source.getName();
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            storedStates = persistentPluginHandler.getPluginStates(names);
        }

        Map<String, PluginState> stateLookup = new HashMap<>(storedStates);
        List<UpdateSourceStatus> result = new ArrayList<>(sources.size());

        for (UpdateSource source : sources) {
            if (source == null) {
                continue;
            }

            String sourceName = source.getName();
            Path targetPath = resolveTargetPath(source);
            PluginState pluginState = sourceName != null ? stateLookup.get(sourceName) : null;
            Integer build = pluginState != null && pluginState.build() >= 0 ? pluginState.build() : null;
            String version = pluginState != null ? pluginState.version() : null;

            String pluginName = resolvePluginName(source, targetPath);
            boolean autoUpdate = true;
            if (pluginUpdateSettingsRepository != null) {
                PluginUpdateSettings settings = pluginUpdateSettingsRepository.getSettings(pluginName);
                autoUpdate = settings.autoUpdateEnabled();
            }

            result.add(new UpdateSourceStatus(
                    sourceName,
                    pluginName,
                    source.getTargetDirectory(),
                    targetPath,
                    build,
                    version,
                    autoUpdate
            ));
        }

        return result;
    }

    private Path resolveTargetPath(UpdateSource source) {
        if (source == null) {
            return null;
        }
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File serverFolder = plugin.getServer().getWorldContainer().getAbsoluteFile();
        File base = source.getTargetDirectory() == TargetDirectory.SERVER ? serverFolder : pluginsFolder;
        if (base == null) {
            return null;
        }
        String filename = source.getFilename();
        File destination = filename != null && !filename.isBlank()
                ? new File(base, filename)
                : base;
        return destination.toPath().toAbsolutePath().normalize();
    }

    private String resolvePluginName(UpdateSource source, Path targetPath) {
        if (source == null) {
            return null;
        }
        String installedPlugin = source.getInstalledPluginName();
        if (installedPlugin != null && !installedPlugin.isBlank()) {
            return installedPlugin;
        }
        if (pluginLifecycleManager == null || targetPath == null) {
            return null;
        }
        return pluginLifecycleManager.findByPath(targetPath)
                .map(ManagedPlugin::getName)
                .orElse(null);
    }

    public record UpdateSourceStatus(String sourceName,
                                     String pluginName,
                                     TargetDirectory targetDirectory,
                                     Path targetPath,
                                     Integer lastBuild,
                                     String lastVersion,
                                     boolean autoUpdateEnabled) {

        public boolean hasBuildInformation() {
            return lastBuild != null || (lastVersion != null && !lastVersion.isBlank());
        }

        public String displayName() {
            return sourceName != null ? sourceName : "Unknown source";
        }

        public String pluginDisplayName() {
            return pluginName != null && !pluginName.isBlank() ? pluginName : "unassigned";
        }

        public String versionLabel() {
            boolean hasVersion = lastVersion != null && !lastVersion.isBlank();
            boolean hasBuild = lastBuild != null;
            if (hasVersion && hasBuild) {
                return "Version " + lastVersion + " (Build " + lastBuild + ")";
            }
            if (hasVersion) {
                return "Version " + lastVersion;
            }
            if (hasBuild) {
                return "Build " + lastBuild;
            }
            return "No installation recorded";
        }
    }
}
