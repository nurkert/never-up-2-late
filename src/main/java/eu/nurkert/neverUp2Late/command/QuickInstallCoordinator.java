package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.fetcher.AssetPatternBuilder;
import eu.nurkert.neverUp2Late.fetcher.GithubReleaseFetcher;
import eu.nurkert.neverUp2Late.fetcher.exception.AssetSelectionRequiredException;
import eu.nurkert.neverUp2Late.fetcher.exception.CompatibilityMismatchException;
import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.util.ArchiveUtils;
import eu.nurkert.neverUp2Late.util.ArchiveUtils.ArchiveEntry;
import eu.nurkert.neverUp2Late.util.FileNameSanitizer;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.entity.Player;

import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleException;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class QuickInstallCoordinator {

    private static final Set<String> MODRINTH_SEGMENT_PREFIXES = Set.of(
            "plugin",
            "plugins",
            "project",
            "projects",
            "mod",
            "mods",
            "modpack",
            "modpacks",
            "datapack",
            "datapacks",
            "resourcepack",
            "resourcepacks",
            "shaderpack",
            "shaderpacks",
            "shader",
            "shaders"
    );

    private static final Set<String> CURSEFORGE_TRAILING_SEGMENTS = Set.of(
            "files",
            "download",
            "relations",
            "changelog",
            "description",
            "issues",
            "images",
            "comments",
            "wiki",
            "source"
    );

    private static final Set<String> CURSEFORGE_CATEGORY_SEGMENTS = Set.of(
            "minecraft",
            "mc-mods",
            "bukkit-plugins",
            "customization",
            "texture-packs",
            "worlds",
            "maps",
            "addons",
            "modpacks",
            "shaderpacks",
            "shaders",
            "datapacks",
            "data-packs",
            "resourcepacks",
            "resource-packs"
    );

    private static final Pattern CURSEFORGE_DATA_PROJECT_ID_PATTERN = Pattern.compile(
            "data-project-id\\s*=\\s*\"?(\\d+)\"?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CURSEFORGE_SPAN_PROJECT_ID_PATTERN = Pattern.compile(
            "project-id[^>]*>(\\d+)<",
            Pattern.CASE_INSENSITIVE
    );

    private final JavaPlugin plugin;
    private final BukkitScheduler scheduler;
    private final FileConfiguration configuration;
    private final UpdateSourceRegistry updateSourceRegistry;
    private final eu.nurkert.neverUp2Late.handlers.UpdateHandler updateHandler;
    private final eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler persistentPluginHandler;
    private final eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository pluginUpdateSettingsRepository;
    private final eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager pluginLifecycleManager;
    private final HttpClient httpClient;
    private final Logger logger;
    private final String messagePrefix;
    private final Map<String, PendingSelection> pendingSelections = new ConcurrentHashMap<>();
    private final Map<String, ArchivePendingSelection> pendingArchiveSelections = new ConcurrentHashMap<>();
    private final Map<String, CompatibilityPending> pendingCompatibilityConfirmations = new ConcurrentHashMap<>();
    private final ArtifactDownloader artifactDownloader;
    private final boolean ignoreCompatibilityWarnings;
    private final Object configurationLock = new Object();

    public QuickInstallCoordinator(PluginContext context) {
        this.plugin = context.getPlugin();
        this.scheduler = context.getScheduler();
        this.configuration = context.getConfiguration();
        this.updateSourceRegistry = context.getUpdateSourceRegistry();
        this.updateHandler = context.getUpdateHandler();
        this.persistentPluginHandler = context.getPersistentPluginHandler();
        this.pluginUpdateSettingsRepository = context.getPluginUpdateSettingsRepository();
        this.pluginLifecycleManager = context.getPluginLifecycleManager();
        this.artifactDownloader = Objects.requireNonNullElseGet(context.getArtifactDownloader(), ArtifactDownloader::new);
        this.httpClient = HttpClient.builder()
                .accept("text/html,application/xhtml+xml")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();
        this.logger = plugin.getLogger();
        this.messagePrefix = ChatColor.GRAY + "[" + ChatColor.AQUA + "nu2l" + ChatColor.GRAY + "] " + ChatColor.RESET;
        this.ignoreCompatibilityWarnings = configuration.getBoolean("quickInstall.ignoreCompatibilityWarnings", false);
    }

    public void install(CommandSender sender, String rawUrl) {
        install(sender, rawUrl, null);
    }

    public void installForPlugin(CommandSender sender, String pluginName, String rawUrl) {
        install(sender, rawUrl, pluginName);
    }

    public void rollback(CommandSender sender, String sourceName) {
        if (!hasPermission(sender, Permissions.INSTALL)) {
            return;
        }
        String target = sourceName != null ? sourceName.trim() : "";
        if (target.isEmpty()) {
            send(sender, ChatColor.RED + "Please specify the update source to roll back.");
            return;
        }
        scheduler.runTaskAsynchronously(plugin, () -> executeRollback(sender, target));
    }

    public List<String> getRollbackSuggestions() {
        return updateSourceRegistry.getSources().stream()
                .map(UpdateSource::getName)
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private void install(CommandSender sender, String rawUrl, String forcedPluginName) {
        if (!hasPermission(sender, Permissions.INSTALL)) {
            return;
        }
        String url = rawUrl != null ? rawUrl.trim() : "";
        if (url.isEmpty()) {
            send(sender, ChatColor.RED + "Please provide a valid URL.");
            return;
        }

        clearPendingSelection(sender);

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            send(sender, ChatColor.RED + "The URL is invalid: " + e.getMessage());
            return;
        }

        InstallationPlan plan;
        try {
            plan = analyse(uri, url);
        } catch (IllegalArgumentException e) {
            send(sender, ChatColor.RED + e.getMessage());
            return;
        }

        plan.setSourceName(ensureUniqueName(plan.getSuggestedName()));
        if (forcedPluginName != null && !forcedPluginName.isBlank()) {
            plan.setInstalledPluginName(forcedPluginName);
            send(sender, ChatColor.GRAY + "Linking with selected plugin: " + forcedPluginName);
        } else {
            detectInstalledPlugin(plan).ifPresent(installed -> {
                plan.setInstalledPluginName(installed);
                send(sender, ChatColor.GRAY + "Linking with installed plugin: " + installed);
            });
        }

        send(sender, ChatColor.AQUA + "Detected source: " + plan.getDisplayName() + " (" + plan.getProvider() + ")");

        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, plan));
    }

    private void executeRollback(CommandSender sender, String sourceName) {
        Optional<UpdateSource> optionalSource = updateSourceRegistry.findSource(sourceName);
        if (optionalSource.isEmpty()) {
            send(sender, ChatColor.RED + "No update source named " + sourceName + " is registered.");
            return;
        }

        UpdateSource source = optionalSource.get();
        Path destination = resolveDestination(source);
        if (destination == null) {
            send(sender, ChatColor.RED + "Cannot determine the destination file for " + source.getName() + ".");
            return;
        }

        try {
            Optional<ArtifactDownloader.RestorationResult> restoration = artifactDownloader.restorePreviousBackup(
                    destination,
                    source.getInstalledPluginName(),
                    source.getName()
            );

            if (restoration.isEmpty()) {
                send(sender, ChatColor.RED + "No backups available for " + source.getName() + ".");
                return;
            }

            ArtifactDownloader.RestorationResult result = restoration.get();
            String backupName = Optional.ofNullable(result.getOriginalBackupPath().getFileName())
                    .map(Path::toString)
                    .orElse(result.getOriginalBackupPath().toString());
            String destinationName = Optional.ofNullable(destination.getFileName())
                    .map(Path::toString)
                    .orElse(destination.toString());

            logger.log(Level.INFO, "Restored backup {0} for {1} to {2}",
                    new Object[]{backupName, source.getName(), destinationName});

            send(sender, ChatColor.GREEN + "Restored backup " + ChatColor.AQUA + backupName
                    + ChatColor.GREEN + " for " + ChatColor.AQUA + source.getName()
                    + ChatColor.GREEN + " → " + ChatColor.AQUA + destinationName + ChatColor.GREEN + ".");

            triggerLifecycleAfterRollback(sender, source, destination);
        } catch (IOException ex) {
            logger.log(Level.WARNING,
                    "Failed to restore backup for {0}: {1}",
                    new Object[]{source.getName(), ex.getMessage()});
            send(sender, ChatColor.RED + "Failed to restore backup: " + ex.getMessage());
        }
    }

    private void triggerLifecycleAfterRollback(CommandSender sender, UpdateSource source, Path destination) {
        if (pluginLifecycleManager == null || source.getTargetDirectory() != TargetDirectory.PLUGINS) {
            return;
        }

        scheduler.runTask(plugin, () -> {
            String preferredName = trimToNull(source.getInstalledPluginName());
            try {
                boolean reloaded = preferredName != null
                        ? pluginLifecycleManager.reloadPlugin(preferredName)
                        : pluginLifecycleManager.reloadPlugin(destination);
                if (reloaded) {
                    String name = preferredName != null ? preferredName
                            : Optional.ofNullable(destination.getFileName()).map(Path::toString).orElse(destination.toString());
                    send(sender, ChatColor.GREEN + "Reloaded plugin " + ChatColor.AQUA + name
                            + ChatColor.GREEN + " after rollback.");
                } else {
                    send(sender, ChatColor.YELLOW + "Rollback completed, but the plugin could not be reloaded automatically.");
                }
            } catch (PluginLifecycleException ex) {
                logger.log(Level.WARNING,
                        "Failed to reload plugin after rollback for {0}: {1}",
                        new Object[]{source.getName(), ex.getMessage()});
                send(sender, ChatColor.RED + "Rollback succeeded, but reloading the plugin failed: " + ex.getMessage());
            }
        });
    }

    private Path resolveDestination(UpdateSource source) {
        if (source == null || plugin == null) {
            return null;
        }
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File serverFolder = plugin.getServer().getWorldContainer().getAbsoluteFile();
        File directory = source.getTargetDirectory() == TargetDirectory.SERVER ? serverFolder : pluginsFolder;
        return new File(directory, source.getFilename()).toPath();
    }

    private void deduplicateExistingSources(CommandSender sender) {
        if (updateSourceRegistry == null) {
            return;
        }

        Map<Path, Integer> usage = computePathUsage();
        Map<String, List<UpdateSource>> groups = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>();

        for (UpdateSource source : updateSourceRegistry.getSources()) {
            String installed = normalizePluginName(source.getInstalledPluginName());
            if (installed != null) {
                groups.computeIfAbsent("plugin:" + installed, key -> new ArrayList<>()).add(source);
            }
            Path destination = normalizePath(resolveDestination(source));
            if (destination != null) {
                groups.computeIfAbsent("path:" + destination, key -> new ArrayList<>()).add(source);
            }
        }

        for (List<UpdateSource> group : groups.values()) {
            List<UpdateSource> candidates = new ArrayList<>();
            for (UpdateSource source : group) {
                if (!removed.contains(source.getName())) {
                    candidates.add(source);
                }
            }
            if (candidates.size() <= 1) {
                continue;
            }
            UpdateSource primary = selectPrimarySource(candidates, null);
            if (primary == null) {
                continue;
            }
            List<UpdateSource> redundant = new ArrayList<>(candidates);
            redundant.remove(primary);
            if (redundant.isEmpty()) {
                continue;
            }
            removed.addAll(cleanupRedundantSources(sender, primary, redundant, usage));
        }
    }

    private Map<Path, Integer> computePathUsage() {
        Map<Path, Integer> usage = new HashMap<>();
        if (updateSourceRegistry == null) {
            return usage;
        }
        for (UpdateSource source : updateSourceRegistry.getSources()) {
            Path destination = normalizePath(resolveDestination(source));
            if (destination != null) {
                usage.merge(destination, 1, Integer::sum);
            }
        }
        return usage;
    }

    private Set<String> cleanupRedundantSources(CommandSender sender,
                                                UpdateSource primary,
                                                List<UpdateSource> redundant,
                                                Map<Path, Integer> usage) {
        if (updateSourceRegistry == null || primary == null || redundant == null || redundant.isEmpty()) {
            return Set.of();
        }

        Set<String> removed = new LinkedHashSet<>();
        Path primaryPath = normalizePath(resolveDestination(primary));

        for (UpdateSource duplicate : redundant) {
            if (!updateSourceRegistry.unregisterSource(duplicate.getName())) {
                continue;
            }
            removed.add(duplicate.getName());
            Path duplicatePath = normalizePath(resolveDestination(duplicate));
            if (duplicatePath == null) {
                continue;
            }
            int remaining = usage.getOrDefault(duplicatePath, 1) - 1;
            if (remaining <= 0) {
                usage.remove(duplicatePath);
            } else {
                usage.put(duplicatePath, remaining);
            }
            if (remaining <= 0 && (primaryPath == null || !primaryPath.equals(duplicatePath))) {
                try {
                    Files.deleteIfExists(duplicatePath);
                    logger.log(Level.INFO, "Removed duplicate plugin artifact {0}", duplicatePath);
                    if (sender != null) {
                        send(sender, ChatColor.GRAY + "Removed duplicate file " + duplicatePath.getFileName());
                    }
                } catch (IOException ex) {
                    logger.log(Level.WARNING, "Failed to delete duplicate artifact " + duplicatePath, ex);
                }
            }
        }

        if (!removed.isEmpty() && sender != null) {
            send(sender, ChatColor.YELLOW + "Removed duplicate update sources: " + String.join(", ", removed));
        }

        return removed;
    }

    private List<UpdateSource> findConflictingSources(InstallationPlan plan) {
        if (updateSourceRegistry == null || plan == null) {
            return List.of();
        }

        Set<UpdateSource> matches = new LinkedHashSet<>();
        String desiredFilename = normalizeFilename(plan.getFilename());
        Set<String> candidateNames = new HashSet<>();
        candidateNames.add(normalizePluginName(plan.getSuggestedName()));
        candidateNames.add(normalizePluginName(plan.getSourceName()));
        candidateNames.addAll(plan.getPluginNameCandidates().stream()
                .map(this::normalizePluginName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        Object installedOption = plan.getOptions().get("installedPlugin");
        if (installedOption instanceof String installed) {
            String normalized = normalizePluginName(installed);
            if (normalized != null) {
                candidateNames.add(normalized);
            }
        }

        for (UpdateSource source : updateSourceRegistry.getSources()) {
            if (source.getTargetDirectory() != plan.getTargetDirectory()) {
                continue;
            }
            String sourceFilename = normalizeFilename(source.getFilename());
            if (desiredFilename != null && desiredFilename.equals(sourceFilename)) {
                matches.add(source);
                continue;
            }
            String installed = normalizePluginName(source.getInstalledPluginName());
            if (installed != null && candidateNames.contains(installed)) {
                matches.add(source);
                continue;
            }
            String normalizedName = normalizePluginName(source.getName());
            if (normalizedName != null && candidateNames.contains(normalizedName)) {
                matches.add(source);
            }
        }

        return new ArrayList<>(matches);
    }

    private UpdateSource selectPrimarySource(List<UpdateSource> candidates, InstallationPlan plan) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (plan != null) {
            String preferred = normalizePluginName(plan.getSuggestedName());
            for (UpdateSource candidate : candidates) {
                if (preferred != null && preferred.equals(normalizePluginName(candidate.getName()))) {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    private Path normalizePath(Path path) {
        return path != null ? path.toAbsolutePath().normalize() : null;
    }

    private String normalizeFilename(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePluginName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replace(" ", "");
        normalized = normalized.replace("-", "");
        normalized = normalized.replace("_", "");
        return normalized.isEmpty() ? null : normalized;
    }

    private void prepareAndInstall(CommandSender sender, InstallationPlan plan) {
        send(sender, ChatColor.YELLOW + "Loading version information…");
        eu.nurkert.neverUp2Late.fetcher.UpdateFetcher fetcher;
        try {
            fetcher = updateSourceRegistry.createFetcher(plan.getFetcherType(), plan.getOptions());
            fetcher.loadLatestBuildInfo();
            String downloadUrl = Objects.requireNonNull(fetcher.getLatestDownloadUrl(), "downloadUrl");
            plan.setLatestBuild(fetcher.getLatestBuild());
            plan.setLatestVersion(fetcher.getLatestVersion());
            plan.setDownloadUrl(downloadUrl);
            plan.setFilename(determineFilename(downloadUrl, plan.getDefaultFilename()));
        } catch (AssetSelectionRequiredException selection) {
            logger.log(Level.INFO, "Asset selection required for {0}: {1}", new Object[]{plan.getDisplayName(), selection.getMessage()});
            requestAssetSelection(sender, plan, selection);
            return;
        } catch (CompatibilityMismatchException compatibility) {
            logger.log(Level.INFO, "Compatibility confirmation required for {0}: {1}",
                    new Object[]{plan.getDisplayName(), compatibility.getMessage()});
            requestCompatibilityOverride(sender, plan, compatibility);
            return;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to prepare installation for " + plan.getDisplayName(), e);
            send(sender, ChatColor.RED + "Failed to load version information: " + e.getMessage());
            return;
        }

        if (!ensureArchiveConfiguration(sender, plan, fetcher)) {
            return;
        }

        send(sender, ChatColor.GREEN + "Latest version: " + plan.getLatestVersionInfo());

        scheduler.runTask(plugin, () -> finalizeInstallation(sender, plan));
    }

    public void handleAssetSelection(CommandSender sender, String selectionInput) {
        if (!hasPermission(sender, Permissions.INSTALL)) {
            return;
        }
        if (selectionInput == null || selectionInput.isBlank()) {
            send(sender, ChatColor.RED + "Please specify the number of the desired file.");
            return;
        }

        String key = selectionKey(sender);
        PendingSelection pending = pendingSelections.get(key);
        if (pending == null) {
            handleArchiveSelection(sender, selectionInput, key);
            return;
        }

        int index;
        try {
            index = Integer.parseInt(selectionInput.trim());
        } catch (NumberFormatException ex) {
            send(sender, ChatColor.RED + "Please enter a valid number.");
            return;
        }

        if (index < 1 || index > pending.assets().size()) {
            send(sender, ChatColor.RED + "Invalid selection. Please choose a number between 1 and " + pending.assets().size() + ".");
            return;
        }

        AssetSelectionRequiredException.ReleaseAsset asset = pending.assets().get(index - 1);
        String assetName = assetLabel(asset);
        List<String> candidateNames = pending.assets().stream()
                .map(this::assetLabel)
                .toList();
        String pattern = AssetPatternBuilder.build(assetName, candidateNames);

        pending.plan().getOptions().put("assetPattern", pattern);
        pendingSelections.remove(key);

        send(sender, ChatColor.GREEN + "Using asset \"" + assetName + "\". Regex created automatically.");
        if (asset.archive()) {
            send(sender, ChatColor.GOLD + "Note: The selected asset is an archive. NU2L will automatically extract the matching JAR file.");
        }

        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, pending.plan()));
    }

    public void confirmCompatibilityOverride(CommandSender sender) {
        processCompatibilityDecision(sender, true);
    }

    public void cancelCompatibilityOverride(CommandSender sender) {
        processCompatibilityDecision(sender, false);
    }

    private void processCompatibilityDecision(CommandSender sender, boolean ignoreWarning) {
        String key = selectionKey(sender);
        CompatibilityPending pending = pendingCompatibilityConfirmations.remove(key);
        if (pending == null) {
            send(sender, ChatColor.RED + "There is no pending compatibility warning.");
            return;
        }

        if (!ignoreWarning) {
            send(sender, ChatColor.YELLOW + "Installation cancelled. Compatibility warning not ignored.");
            return;
        }

        pending.plan().getOptions().put("ignoreCompatibilityWarnings", true);
        send(sender, ChatColor.YELLOW + "Ignoring compatibility warning and retrying with the latest version…");
        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, pending.plan()));
    }

    private void handleArchiveSelection(CommandSender sender, String selectionInput, String key) {
        ArchivePendingSelection pending = pendingArchiveSelections.get(key);
        if (pending == null) {
            send(sender, ChatColor.RED + "There is no pending selection.");
            return;
        }

        int index;
        try {
            index = Integer.parseInt(selectionInput.trim());
        } catch (NumberFormatException ex) {
            send(sender, ChatColor.RED + "Please enter a valid number.");
            return;
        }

        if (index < 1 || index > pending.entries().size()) {
            send(sender, ChatColor.RED + "Invalid selection. Please choose a number between 1 and " + pending.entries().size() + ".");
            return;
        }

        ArchiveEntry selected = pending.entries().get(index - 1);
        List<String> candidateNames = pending.entries().stream().map(ArchiveEntry::fullPath).toList();
        String pattern = AssetPatternBuilder.build(selected.fullPath(), candidateNames);

        pending.plan().getOptions().put("archiveEntryPattern", pattern);
        pending.plan().setFilename(extractFileNameForArchive(selected));
        pendingArchiveSelections.remove(key);

        send(sender, ChatColor.GREEN + "Using JAR \"" + selected.fullPath() + "\" from the archive.");
        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, pending.plan()));
    }

    private boolean ensureArchiveConfiguration(CommandSender sender,
                                               InstallationPlan plan,
                                               eu.nurkert.neverUp2Late.fetcher.UpdateFetcher fetcher) {
        if (!(fetcher instanceof GithubReleaseFetcher githubFetcher) || !githubFetcher.isSelectedAssetArchive()) {
            plan.getOptions().remove("archiveEntryPattern");
            return true;
        }

        String downloadUrl = plan.getDownloadUrl();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            send(sender, ChatColor.RED + "Error: archive download URL missing.");
            return false;
        }

        List<ArchiveEntry> entries;
        try {
            entries = inspectArchive(downloadUrl);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to inspect archive for " + plan.getDisplayName(), e);
            send(sender, ChatColor.RED + "Failed to inspect archive: " + e.getMessage());
            return false;
        }

        if (entries.isEmpty()) {
            send(sender, ChatColor.RED + "The archive does not contain any JAR files.");
            return false;
        }

        Optional<Pattern> configuredPattern = githubFetcher.getArchiveEntryPattern();
        if (configuredPattern.isPresent()) {
            Pattern pattern = configuredPattern.get();
            Optional<ArchiveEntry> match = entries.stream()
                    .filter(entry -> pattern.matcher(entry.fullPath()).matches()
                            || pattern.matcher(entry.fileName()).matches())
                    .findFirst();
            if (match.isPresent()) {
                applyArchiveSelection(plan, match.get(), entries);
                return true;
            }
            logger.log(Level.INFO,
                    "Configured archiveEntryPattern did not match any entry for {0}: {1}",
                    new Object[]{plan.getDisplayName(), pattern.pattern()});
        }

        if (entries.size() == 1) {
            applyArchiveSelection(plan, entries.get(0), entries);
            send(sender, ChatColor.GRAY + "Found JAR in archive: " + entries.get(0).fullPath());
            return true;
        }

        requestArchiveEntrySelection(sender, plan, entries);
        return false;
    }

    private List<ArchiveEntry> inspectArchive(String downloadUrl) throws IOException {
        Path tempFile = Files.createTempFile("nu2l-archive-", ".zip");
        try {
            ArtifactDownloader.DownloadRequest request = ArtifactDownloader.DownloadRequest.builder()
                    .url(downloadUrl)
                    .destination(tempFile)
                    .build();
            artifactDownloader.download(request);
            return ArchiveUtils.listJarEntries(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void applyArchiveSelection(InstallationPlan plan,
                                       ArchiveEntry selected,
                                       List<ArchiveEntry> allEntries) {
        List<String> candidateNames = allEntries.stream().map(ArchiveEntry::fullPath).toList();
        String pattern = AssetPatternBuilder.build(selected.fullPath(), candidateNames);
        plan.getOptions().put("archiveEntryPattern", pattern);
        plan.setFilename(extractFileNameForArchive(selected));
    }

    private void requestArchiveEntrySelection(CommandSender sender,
                                              InstallationPlan plan,
                                              List<ArchiveEntry> entries) {
        String key = selectionKey(sender);
        pendingArchiveSelections.put(key, new ArchivePendingSelection(plan, List.copyOf(entries)));

        send(sender, ChatColor.GOLD + "The archive contains multiple JAR files:");
        for (int i = 0; i < entries.size(); i++) {
            ArchiveEntry entry = entries.get(i);
            send(sender, ChatColor.GRAY + String.valueOf(i + 1) + ChatColor.DARK_GRAY + ". "
                    + ChatColor.AQUA + entry.fullPath());
        }
        send(sender, ChatColor.YELLOW + "Please use /nu2l select <number> to choose the desired file.");
    }

    private String extractFileNameForArchive(ArchiveEntry entry) {
        String fileName = Optional.ofNullable(entry.fileName()).orElse(entry.fullPath());
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0 && slash < fileName.length() - 1) {
            fileName = fileName.substring(slash + 1);
        }
        return fileName;
    }

    private void requestAssetSelection(CommandSender sender,
                                       InstallationPlan plan,
                                       AssetSelectionRequiredException selection) {
        String key = selectionKey(sender);
        pendingSelections.put(key, new PendingSelection(plan, selection.getAssets(), selection.getAssetType(), selection.getReleaseTag()));

        String typeLabel = switch (selection.getAssetType()) {
            case ARCHIVE -> "archives";
            case JAR -> "JAR files";
            default -> "assets";
        };

        send(sender, ChatColor.GOLD + "Found multiple " + typeLabel + " for " + plan.getDisplayName()
                + " (" + selection.getReleaseTag() + "):");
        List<AssetSelectionRequiredException.ReleaseAsset> assets = selection.getAssets();
        for (int i = 0; i < assets.size(); i++) {
            AssetSelectionRequiredException.ReleaseAsset asset = assets.get(i);
            String label = assetLabel(asset);
            String suffix = asset.archive() ? ChatColor.YELLOW + " (archive)" : "";
            send(sender, ChatColor.GRAY + String.valueOf(i + 1) + ChatColor.DARK_GRAY + ". "
                    + ChatColor.AQUA + label + suffix);
        }
        send(sender, ChatColor.YELLOW + "Please use /nu2l select <number> to choose the desired asset.");
        if (selection.getAssetType() == AssetSelectionRequiredException.AssetType.ARCHIVE
                || assets.stream().anyMatch(AssetSelectionRequiredException.ReleaseAsset::archive)) {
            send(sender, ChatColor.GOLD + "Note: Archives are extracted automatically; please choose the desired file.");
        }
    }

    private void requestCompatibilityOverride(CommandSender sender,
                                              InstallationPlan plan,
                                              CompatibilityMismatchException compatibility) {
        String key = selectionKey(sender);
        pendingCompatibilityConfirmations.put(key, new CompatibilityPending(plan, compatibility));

        send(sender, ChatColor.GOLD + "No compatible version was advertised for " + ChatColor.AQUA
                + plan.getDisplayName() + ChatColor.GOLD + ".");
        send(sender, ChatColor.YELLOW + compatibility.getMessage());

        String serverVersion = trimToNull(compatibility.getServerVersion());
        if (serverVersion != null) {
            send(sender, ChatColor.GRAY + "Your server reports Minecraft version " + ChatColor.AQUA
                    + serverVersion + ChatColor.GRAY + ".");
        }

        List<String> available = compatibility.getAvailableVersions();
        if (!available.isEmpty()) {
            String joined = String.join(ChatColor.GRAY + ", " + ChatColor.AQUA, available);
            send(sender, ChatColor.GRAY + "Listed versions: " + ChatColor.AQUA + joined);
        }

        send(sender, ChatColor.YELLOW + "Use /nu2l ignore to install the latest version anyway or /nu2l cancel to abort.");
    }

    private void finalizeInstallation(CommandSender sender, InstallationPlan plan) {
        deduplicateExistingSources(sender);

        List<UpdateSource> conflicts = findConflictingSources(plan);
        if (!conflicts.isEmpty()) {
            UpdateSource primary = selectPrimarySource(conflicts, plan);
            if (primary != null) {
                List<UpdateSource> redundant = new ArrayList<>(conflicts);
                redundant.remove(primary);
                if (!redundant.isEmpty()) {
                    cleanupRedundantSources(sender, primary, redundant, computePathUsage());
                }
                send(sender, ChatColor.YELLOW + "Source already exists, starting update…");
                updateHandler.runJobNow(primary, sender);
                return;
            }
        }

        if (updateSourceRegistry.hasSource(plan.getSourceName())) {
            send(sender, ChatColor.YELLOW + "Source already exists, starting update…");
            UpdateSource existing = updateSourceRegistry.findSource(plan.getSourceName()).orElse(null);
            if (existing != null) {
                updateHandler.runJobNow(existing, sender);
            }
            return;
        }

        ConfigurationSnapshot snapshot = persistSourceConfiguration(plan, sender);
        if (snapshot == null) {
            return;
        }

        UpdateSource source;
        try {
            source = updateSourceRegistry.registerDynamicSource(
                    plan.getSourceName(),
                    plan.getFetcherType(),
                    plan.getTargetDirectory(),
                    plan.getFilename(),
                    plan.getOptions()
            );
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to register dynamic source " + plan.getSourceName(), e);
            restoreConfiguration(snapshot);
            send(sender, ChatColor.RED + "Could not register source: " + e.getMessage());
            return;
        }

        send(sender, ChatColor.GREEN + "Registered update source " + ChatColor.AQUA + source.getName()
                + ChatColor.GREEN + " → " + ChatColor.AQUA + source.getFilename() + ChatColor.GREEN + ".");
        send(sender, ChatColor.GREEN + "Starting installation…");
        updateHandler.runJobNow(source, sender);
    }

    private ConfigurationSnapshot persistSourceConfiguration(InstallationPlan plan, CommandSender sender) {
        synchronized (configurationLock) {
            ConfigurationSnapshot snapshot = createSnapshot();
            if (snapshot == null) {
                send(sender, ChatColor.RED + "Could not create a configuration backup. Aborting.");
                return null;
            }
            try {
                applyPlanToConfiguration(plan);
                plugin.saveConfig();
                return snapshot;
            } catch (Exception ex) {
                logger.log(Level.SEVERE,
                        "Failed to persist configuration for " + plan.getSourceName() + ':', ex);
                restoreConfiguration(snapshot);
                send(sender, ChatColor.RED + "Could not write configuration: " + ex.getMessage());
                return null;
            }
        }
    }

    private ConfigurationSnapshot createSnapshot() {
        try {
            return new ConfigurationSnapshot(configuration.saveToString());
        } catch (Exception ex) {
            logger.log(Level.FINE, "Failed to create configuration snapshot", ex);
            return null;
        }
    }

    private void restoreConfiguration(ConfigurationSnapshot snapshot) {
        if (snapshot == null || snapshot.data() == null) {
            return;
        }
        synchronized (configurationLock) {
            try {
                configuration.loadFromString(snapshot.data());
                plugin.saveConfig();
            } catch (InvalidConfigurationException | RuntimeException ex) {
                logger.log(Level.SEVERE, "Failed to restore configuration after error", ex);
            }
        }
    }

    private void applyPlanToConfiguration(InstallationPlan plan) {
        ConfigurationSection section = configuration.getConfigurationSection("updates.sources");
        if (section != null && !section.getKeys(false).isEmpty()) {
            if (section.contains(plan.getSourceName())) {
                section.set(plan.getSourceName(), null);
            }
            ConfigurationSection newSection = section.createSection(plan.getSourceName());
            populateSection(newSection, plan);
            return;
        }

        List<Map<?, ?>> existing = new ArrayList<>(configuration.getMapList("updates.sources"));
        existing.removeIf(map -> plan.getSourceName().equalsIgnoreCase(Objects.toString(map.get("name"), "")));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", plan.getSourceName());
        entry.put("type", plan.getFetcherType());
        entry.put("target", plan.getTargetDirectory().name());
        entry.put("filename", plan.getFilename());
        if (!plan.getOptions().isEmpty()) {
            entry.put("options", new LinkedHashMap<>(plan.getOptions()));
        }
        existing.add(entry);
        configuration.set("updates.sources", existing);
    }

    private void populateSection(ConfigurationSection newSection, InstallationPlan plan) {
        newSection.set("name", plan.getSourceName());
        newSection.set("type", plan.getFetcherType());
        newSection.set("target", plan.getTargetDirectory().name());
        newSection.set("filename", plan.getFilename());
        if (!plan.getOptions().isEmpty()) {
            ConfigurationSection optionsSection = newSection.createSection("options");
            plan.getOptions().forEach(optionsSection::set);
        }
    }

    private Optional<String> detectInstalledPlugin(InstallationPlan plan) {
        PluginManager pluginManager = Bukkit.getPluginManager();
        if (pluginManager == null) {
            return Optional.empty();
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(plan.getDisplayName());
        candidates.addAll(plan.getPluginNameCandidates());
        plan.getHost().ifPresent(candidates::add);

        Map<String, Plugin> plugins = new LinkedHashMap<>();
        for (Plugin plugin : pluginManager.getPlugins()) {
            plugins.put(normalize(plugin.getName()), plugin);
        }

        for (String candidate : candidates) {
            String normalizedCandidate = normalize(candidate);
            Plugin match = plugins.get(normalizedCandidate);
            if (match != null) {
                return Optional.of(match.getName());
            }
        }

        for (String candidate : candidates) {
            String normalizedCandidate = normalize(candidate);
            for (Map.Entry<String, Plugin> entry : plugins.entrySet()) {
                if (entry.getKey().contains(normalizedCandidate) || normalizedCandidate.contains(entry.getKey())) {
                    return Optional.of(entry.getValue().getName());
                }
                PluginDescriptionFile description = entry.getValue().getDescription();
                String website = description.getWebsite();
                if (website != null && plan.getHost().isPresent()
                        && websiteMatchesCandidate(website, plan.getHost().get(), normalizedCandidate)) {
                    return Optional.of(entry.getValue().getName());
                }
            }
        }

        return Optional.empty();
    }

    private boolean websiteMatchesCandidate(String website, String expectedHost, String normalizedCandidate) {
        if (normalizedCandidate == null || normalizedCandidate.isBlank()) {
            return false;
        }

        String trimmedHost = trimWww(expectedHost);
        if (trimmedHost.isEmpty()) {
            return false;
        }

        try {
            URI uri = new URI(website);
            String host = trimWww(Optional.ofNullable(uri.getHost()).orElse(""));
            if (!host.equalsIgnoreCase(trimmedHost)) {
                return false;
            }
            String path = normalize(uri.getPath());
            return !path.isBlank() && path.contains(normalizedCandidate);
        } catch (URISyntaxException ignored) {
            String normalizedWebsite = normalize(website);
            return normalizedWebsite.contains(normalizedCandidate)
                    && normalize(trimmedHost).equals(normalize(extractHostFallback(website)));
        }
    }

    private String trimWww(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim();
        if (value.regionMatches(true, 0, "www.", 0, 4)) {
            return value.substring(4);
        }
        return value;
    }

    private String extractHostFallback(String website) {
        int schemeSeparator = website.indexOf("//");
        int start = schemeSeparator >= 0 ? schemeSeparator + 2 : 0;
        int slash = website.indexOf('/', start);
        String host = slash >= 0 ? website.substring(start, slash) : website.substring(start);
        return trimWww(host);
    }

    private InstallationPlan analyse(URI uri, String originalUrl) {
        String host = Optional.ofNullable(uri.getHost()).map(String::toLowerCase).orElse("");
        if (host.contains("hangar.papermc")) {
            return buildHangarPlan(uri, originalUrl, host);
        }
        if (host.contains("modrinth.com")) {
            return buildModrinthPlan(uri, originalUrl, host);
        }
        if (host.contains("spigotmc.org") || host.contains("api.spiget.org")) {
            return buildSpigotPlan(uri, originalUrl, host);
        }
        if (host.contains("github.com")) {
            return buildGithubPlan(uri, originalUrl, host);
        }
        if (host.contains("curseforge.com")) {
            return buildCurseforgePlan(uri, originalUrl, host);
        }
        if (uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).contains("/job/")) {
            return buildJenkinsPlan(uri, originalUrl, host);
        }
        throw new IllegalArgumentException("This URL is not currently supported.");
    }

    private InstallationPlan buildHangarPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        OwnerSlug ownerSlug = extractOwnerAndSlug(segments, List.of("plugins", "plugin", "project"))
                .orElseThrow(() -> new IllegalArgumentException("Hangar URL must follow the /Owner/Project format."));
        String owner = ownerSlug.owner();
        String slug = ownerSlug.slug();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("project", owner + "/" + slug);
        options.put("platform", "PAPER");
        applyCompatibilityPreference(options);

        String defaultFilename = resolveDefaultFilename(slug, owner + "-" + slug);

        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                "Hangar",
                host,
                "hangar",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(owner);
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        return plan;
    }

    private InstallationPlan buildModrinthPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        String slug = extractModrinthSlug(segments)
                .orElseThrow(() -> new IllegalArgumentException("Could not parse Modrinth URL."));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("project", slug);
        options.put("loaders", List.of("paper", "spigot"));
        applyCompatibilityPreference(options);

        String defaultFilename = resolveDefaultFilename(slug);

        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                "Modrinth",
                host,
                "modrinth",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        return plan;
    }

    private InstallationPlan buildCurseforgePlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        CurseforgeProjectPath projectPath = extractCurseforgeProject(segments)
                .orElseThrow(() -> new IllegalArgumentException("Could not parse CurseForge URL."));

        String slug = projectPath.slug();
        OptionalLong projectId;
        try {
            projectId = fetchCurseforgeProjectId(uri, segments, projectPath.slugIndex());
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to query CurseForge: " + e.getMessage(), e);
        }

        if (projectId.isEmpty()) {
            throw new IllegalArgumentException("Could not determine the CurseForge project ID from the provided URL.");
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("modId", projectId.getAsLong());
        applyCompatibilityPreference(options);

        String defaultFilename = resolveDefaultFilename(slug);

        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                "CurseForge",
                host,
                "curseforge",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        return plan;
    }

    private InstallationPlan buildSpigotPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        SpigotResource resource = extractSpigotResource(segments)
                .orElseThrow(() -> new IllegalArgumentException("Could not parse SpigotMC URL."));

        long resourceId = resource.resourceId();
        String slug = resource.slug();

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("resourceId", resourceId);
        applyCompatibilityPreference(options);

        String defaultFilename = resolveDefaultFilename(slug, slug + "-" + resourceId, "resource-" + resourceId);

        String providerLabel = host != null && host.contains("api.spiget.org") ? "Spiget API" : "SpigotMC";

        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                providerLabel,
                host,
                "spigot",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        if (!slug.equalsIgnoreCase("resource-" + resourceId)) {
            plan.addPluginNameCandidate(slug.replace('-', ' '));
        }
        plan.addPluginNameCandidate(String.valueOf(resourceId));
        return plan;
    }

    private OptionalLong fetchCurseforgeProjectId(URI uri, List<String> segments, int slugIndex) throws IOException {
        String projectUrl = buildCurseforgeProjectUrl(uri, segments, slugIndex);
        try {
            String body = httpClient.get(projectUrl);
            OptionalLong projectId = extractCurseforgeProjectId(body);
            if (projectId.isEmpty()) {
                logger.log(Level.FINE, "CurseForge response from {0} did not include a project id.", projectUrl);
            }
            return projectId;
        } catch (IOException e) {
            logger.log(Level.FINE, "Failed to download CurseForge project page " + projectUrl, e);
            throw e;
        }
    }

    private String buildCurseforgeProjectUrl(URI uri, List<String> segments, int slugIndex) {
        if (segments == null || slugIndex < 0 || slugIndex >= segments.size()) {
            throw new IllegalArgumentException("Invalid CurseForge URL structure.");
        }
        String authority = uri.getAuthority();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("CurseForge URL must include a host.");
        }
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("https");
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 0; i <= slugIndex; i++) {
            pathBuilder.append('/').append(segments.get(i));
        }
        return scheme + "://" + authority + pathBuilder;
    }

    private OptionalLong extractCurseforgeProjectId(String html) {
        if (html == null || html.isBlank()) {
            return OptionalLong.empty();
        }
        Pattern[] patterns = {CURSEFORGE_DATA_PROJECT_ID_PATTERN, CURSEFORGE_SPAN_PROJECT_ID_PATTERN};
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(html);
            if (!matcher.find()) {
                continue;
            }
            String value = matcher.group(1);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                long id = Long.parseLong(value.trim());
                if (id > 0) {
                    return OptionalLong.of(id);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed matches and continue with the next pattern
            }
        }
        return OptionalLong.empty();
    }

    static Optional<String> extractModrinthSlug(List<String> segments) {
        String slug = null;
        boolean expectSlug = false;

        for (String rawSegment : segments) {
            String segment = decode(rawSegment);
            if (segment.isBlank()) {
                continue;
            }

            String normalized = segment.toLowerCase(Locale.ROOT);

            if (expectSlug) {
                slug = segment;
                break;
            }

            if (MODRINTH_SEGMENT_PREFIXES.contains(normalized)) {
                expectSlug = true;
                continue;
            }

            if (slug == null) {
                slug = segment;
                break;
            }
        }

        return Optional.ofNullable(trimToNull(slug));
    }

    static Optional<SpigotResource> extractSpigotResource(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }

        Long resourceId = null;
        String slug = null;

        for (int i = 0; i < segments.size(); i++) {
            String decoded = decode(segments.get(i));
            String trimmed = trimToNull(decoded);
            if (trimmed == null) {
                continue;
            }

            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if ("resources".equals(normalized) || "resource".equals(normalized)) {
                for (int j = i + 1; j < segments.size(); j++) {
                    SpigotResource parsed = parseSpigotResourceSegment(segments.get(j));
                    if (parsed != null) {
                        resourceId = parsed.resourceId();
                        slug = parsed.slug();
                        break;
                    }
                }
                if (resourceId != null) {
                    break;
                }
                continue;
            }

            if (resourceId == null) {
                SpigotResource parsed = parseSpigotResourceSegment(segments.get(i));
                if (parsed != null) {
                    resourceId = parsed.resourceId();
                    slug = parsed.slug();
                    break;
                }
            }
        }

        if (resourceId == null) {
            return Optional.empty();
        }

        String finalSlug = slug;
        if (finalSlug == null || finalSlug.isBlank()) {
            finalSlug = "resource-" + resourceId;
        }

        return Optional.of(new SpigotResource(resourceId, finalSlug));
    }

    private static SpigotResource parseSpigotResourceSegment(String rawSegment) {
        String decoded = decode(rawSegment);
        String candidate = trimToNull(decoded);
        if (candidate == null) {
            return null;
        }

        int dotIndex = candidate.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex + 1 < candidate.length()) {
            String idPart = candidate.substring(dotIndex + 1);
            if (isNumeric(idPart)) {
                long id = Long.parseLong(idPart);
                String slugPart = trimToNull(candidate.substring(0, dotIndex));
                return new SpigotResource(id, slugPart);
            }
        }

        if (isNumeric(candidate)) {
            long id = Long.parseLong(candidate);
            return new SpigotResource(id, null);
        }

        return null;
    }

    static Optional<CurseforgeProjectPath> extractCurseforgeProject(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }

        int index = segments.size() - 1;
        while (index >= 0) {
            String decoded = decode(segments.get(index));
            String trimmed = decoded.trim();
            if (trimmed.isEmpty()) {
                index--;
                continue;
            }

            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if (CURSEFORGE_TRAILING_SEGMENTS.contains(normalized)) {
                index--;
                continue;
            }

            if (CURSEFORGE_CATEGORY_SEGMENTS.contains(normalized)) {
                index--;
                continue;
            }

            if (isNumeric(normalized)) {
                if (index > 0) {
                    String previous = decode(segments.get(index - 1)).trim().toLowerCase(Locale.ROOT);
                    if (CURSEFORGE_TRAILING_SEGMENTS.contains(previous)) {
                        index -= 2;
                        continue;
                    }
                }
                index--;
                continue;
            }

            String slug = trimToNull(trimmed);
            if (slug == null) {
                index--;
                continue;
            }
            return Optional.of(new CurseforgeProjectPath(index, slug));
        }

        return Optional.empty();
    }

    static Optional<OwnerSlug> extractOwnerAndSlug(List<String> segments, Collection<String> segmentsToIgnore) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }

        Set<String> ignore = new HashSet<>();
        if (segmentsToIgnore != null) {
            for (String segment : segmentsToIgnore) {
                if (segment != null && !segment.isBlank()) {
                    ignore.add(segment.toLowerCase(Locale.ROOT));
                }
            }
        }

        String owner = null;
        String slug = null;

        for (String rawSegment : segments) {
            String segment = decode(rawSegment);
            if (segment.isBlank()) {
                continue;
            }

            String normalized = segment.toLowerCase(Locale.ROOT);
            if (ignore.contains(normalized)) {
                continue;
            }

            if (owner == null) {
                owner = segment;
                continue;
            }

            slug = segment;
            break;
        }

        owner = trimToNull(owner);
        slug = trimToNull(slug);
        if (owner == null || slug == null) {
            return Optional.empty();
        }

        return Optional.of(new OwnerSlug(owner, slug));
    }

    private InstallationPlan buildGithubPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        OwnerSlug ownerSlug = extractOwnerAndSlug(segments, List.of("repos", "projects", "users", "orgs"))
                .orElseThrow(() -> new IllegalArgumentException("GitHub URL must follow the /Owner/Repository format."));
        String owner = ownerSlug.owner();
        String repository = ownerSlug.slug();

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("owner", owner);
        options.put("repository", repository);
        applyCompatibilityPreference(options);
        String defaultFilename = resolveDefaultFilename(repository, owner + "-" + repository);

        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                "GitHub Releases",
                host,
                "githubRelease",
                repository,
                toDisplayName(repository),
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(repository);
        plan.addPluginNameCandidate(toDisplayName(repository));
        plan.addPluginNameCandidate(owner + " " + repository);
        return plan;
    }

    private InstallationPlan buildJenkinsPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        List<String> jobSegments = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if ("job".equalsIgnoreCase(segments.get(i)) && i + 1 < segments.size()) {
                jobSegments.add(decode(segments.get(++i)));
            }
        }
        if (jobSegments.isEmpty()) {
            throw new IllegalArgumentException("Jenkins URL must contain /job/<name>.");
        }

        String jobPath = String.join("/", jobSegments);
        String baseUrl = buildJenkinsBaseUrl(uri, segments);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("baseUrl", baseUrl);
        options.put("job", jobPath);
        options.put("artifactPattern", "(?i).*\\.jar$");
        applyCompatibilityPreference(options);

        String displayName = jobSegments.get(jobSegments.size() - 1);
        String providerLabel = (host == null || host.isBlank()) ? "Jenkins" : host;
        String defaultFilename = resolveDefaultFilename(displayName, jobPath);

        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                providerLabel,
                host,
                "jenkins",
                sanitizeKey(displayName),
                displayName,
                TargetDirectory.PLUGINS,
                options,
                defaultFilename
        );
        plan.addPluginNameCandidate(displayName);
        plan.addPluginNameCandidate(displayName.replace("-", " "));
        if (displayName.contains("-")) {
            plan.addPluginNameCandidate(displayName.substring(0, displayName.indexOf('-')).trim());
        }
        return plan;
    }

    private void applyCompatibilityPreference(Map<String, Object> options) {
        if (ignoreCompatibilityWarnings && options != null) {
            options.put("ignoreCompatibilityWarnings", true);
        }
    }

    private String buildJenkinsBaseUrl(URI uri, List<String> segments) {
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("https");
        StringBuilder base = new StringBuilder();
        base.append(scheme).append("://").append(uri.getHost());
        if (uri.getPort() > 0) {
            base.append(":").append(uri.getPort());
        }
        int firstJob = -1;
        for (int i = 0; i < segments.size(); i++) {
            if ("job".equalsIgnoreCase(segments.get(i))) {
                firstJob = i;
                break;
            }
        }
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i == firstJob) {
                break;
            }
            path.append('/').append(segments.get(i));
        }
        if (path.length() == 0) {
            path.append('/');
        }
        if (path.charAt(path.length() - 1) != '/') {
            path.append('/');
        }
        base.append(path);
        return base.toString();
    }

    private String determineFilename(String downloadUrl, String fallback) {
        String sanitizedFallback = fallback != null && !fallback.isBlank() ? fallback : null;
        try {
            URI uri = new URI(downloadUrl);
            String path = Optional.ofNullable(uri.getPath()).orElse("");
            int lastSlash = path.lastIndexOf('/');
            String candidate = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            candidate = candidate.split("\\?")[0];
            if (candidate.isBlank() || !candidate.contains(".")) {
                candidate = sanitizedFallback != null ? sanitizedFallback : candidate;
            }
            if (candidate == null || candidate.isBlank()) {
                candidate = "download.jar";
            }
            if (!candidate.contains(".")) {
                candidate = candidate + ".jar";
            }
            return candidate;
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to parse filename from " + downloadUrl, e);
            return sanitizedFallback != null ? sanitizedFallback : fallback;
        }
    }

    private String ensureUniqueName(String base) {
        String candidate = base;
        int counter = 1;
        while (updateSourceRegistry.hasSource(candidate)) {
            candidate = base + "-" + counter++;
        }
        return candidate;
    }

    private void send(CommandSender sender, String message) {
        if (sender == null) {
            return;
        }
        scheduler.runTask(plugin, () -> sender.sendMessage(messagePrefix + message));
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender == null || permission == null || permission.isBlank()) {
            return true;
        }
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, ChatColor.RED + "You do not have the required permission (" + permission + ").");
        return false;
    }

    public void removeManagedPlugin(CommandSender sender, ManagedPlugin target) {
        if (target == null) {
            send(sender, ChatColor.RED + "Unknown plugin – action cancelled.");
            return;
        }
        if (!hasPermission(sender, Permissions.GUI_MANAGE_REMOVE)) {
            return;
        }
        if (pluginLifecycleManager == null) {
            send(sender, ChatColor.RED + "Plugin management is disabled; removal not possible.");
            return;
        }

        scheduler.runTask(plugin, () -> {
            String pluginName = Optional.ofNullable(target.getName()).orElse("Plugin");
            Path jarPath = target.getPath();

            try {
                if (target.isEnabled()) {
                    pluginLifecycleManager.disablePlugin(pluginName);
                }
                if (target.isLoaded()) {
                    pluginLifecycleManager.unloadPlugin(pluginName);
                }
            } catch (PluginLifecycleException ex) {
                send(sender, ChatColor.RED + "Failed to unload " + pluginName + ": " + ex.getMessage());
                logger.log(Level.WARNING, "Failed to unload plugin " + pluginName, ex);
                return;
            }

            if (jarPath != null) {
                try {
                    Files.deleteIfExists(jarPath);
                } catch (IOException ex) {
                    send(sender, ChatColor.RED + "Could not delete file: " + jarPath.getFileName());
                    logger.log(Level.WARNING, "Failed to delete plugin jar " + jarPath, ex);
                    return;
                }
            }

            List<UpdateSource> matchingSources = updateSourceRegistry.getSources().stream()
                    .filter(source -> matchesPluginSource(source, pluginName, jarPath))
                    .toList();

            for (UpdateSource source : matchingSources) {
                if (updateSourceRegistry.unregisterSource(source.getName())) {
                    configuration.set("filenames." + source.getName(), null);
                    persistentPluginHandler.removePluginInfo(source.getName());
                }
            }

            if (!matchingSources.isEmpty()) {
                plugin.saveConfig();
            }

            if (pluginUpdateSettingsRepository != null) {
                pluginUpdateSettingsRepository.removeSettings(pluginName);
            }

            send(sender, ChatColor.GREEN + "Plugin " + ChatColor.AQUA + pluginName + ChatColor.GREEN + " has been removed.");
        });
    }

    private boolean matchesPluginSource(UpdateSource source, String pluginName, Path jarPath) {
        if (source == null) {
            return false;
        }
        String filename = source.getFilename();
        if (jarPath != null && jarPath.getFileName() != null
                && filename != null
                && jarPath.getFileName().toString().equalsIgnoreCase(filename)) {
            return true;
        }
        String installedPlugin = source.getInstalledPluginName();
        return installedPlugin != null && installedPlugin.equalsIgnoreCase(pluginName);
    }

    public void removePlugin(CommandSender sender, String pluginName) {
        if (pluginLifecycleManager == null) {
            send(sender, ChatColor.RED + "Plugin management is disabled; removal not possible.");
            return;
        }
        ManagedPlugin target = pluginLifecycleManager.findByName(pluginName).orElse(null);
        if (target == null) {
            send(sender, ChatColor.RED + "Plugin " + pluginName + " was not found.");
            return;
        }
        removeManagedPlugin(sender, target);
    }

    public void disableAutomaticUpdates(CommandSender sender, ManagedPlugin target) {
        if (target == null) {
            send(sender, ChatColor.RED + "Unknown plugin – action cancelled.");
            return;
        }
        if (!hasPermission(sender, Permissions.GUI_MANAGE_LINK)) {
            return;
        }

        String initialPluginName = Optional.ofNullable(target.getName()).orElse("");
        Path jarPath = target.getPath();

        List<UpdateSource> matchingSources = updateSourceRegistry.getSources().stream()
                .filter(source -> matchesPluginSource(source, initialPluginName, jarPath))
                .toList();

        String pluginName = initialPluginName;
        String displayName = pluginName.isBlank() ? "Plugin" : pluginName;

        if (matchingSources.isEmpty()) {
            send(sender, ChatColor.YELLOW + "No update source is configured for "
                    + ChatColor.AQUA + displayName + ChatColor.YELLOW + ".");
            return;
        }

        if (pluginName.isBlank()) {
            pluginName = matchingSources.stream()
                    .map(UpdateSource::getInstalledPluginName)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("");
            if (!pluginName.isBlank()) {
                displayName = pluginName;
            }
        }

        int removed = 0;
        for (UpdateSource source : matchingSources) {
            if (updateSourceRegistry.unregisterSource(source.getName())) {
                configuration.set("filenames." + source.getName(), null);
                persistentPluginHandler.removePluginInfo(source.getName());
                removed++;
            }
        }

        if (removed > 0) {
            plugin.saveConfig();
        }

        if (pluginUpdateSettingsRepository != null && !pluginName.isBlank()) {
            PluginUpdateSettings current = pluginUpdateSettingsRepository.getSettings(pluginName);
            if (current.autoUpdateEnabled()) {
                pluginUpdateSettingsRepository.saveSettings(pluginName,
                        new PluginUpdateSettings(false, current.behaviour(), current.retainUpstreamFilename()));
            }
        }

        if (removed > 0) {
            send(sender, ChatColor.YELLOW + "Automatic updates for " + ChatColor.AQUA + displayName
                    + ChatColor.YELLOW + " have been disabled.");
            send(sender, ChatColor.GRAY + "The plugin remains installed; only the update source was removed.");
        } else {
            send(sender, ChatColor.RED + "Could not disable automatic updates.");
        }
    }

    public void renameManagedPlugin(CommandSender sender, ManagedPlugin target, String requestedName) {
        if (target == null) {
            send(sender, ChatColor.RED + "Unknown plugin – action cancelled.");
            return;
        }
        if (!hasPermission(sender, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }
        if (pluginLifecycleManager == null) {
            send(sender, ChatColor.RED + "Plugin management is disabled; rename not possible.");
            return;
        }

        scheduler.runTask(plugin, () -> {
            Path currentPath = target.getPath();
            if (currentPath == null) {
                send(sender, ChatColor.RED + "Could not determine a file path.");
                return;
            }

            String sanitized = FileNameSanitizer.sanitizeJarFilename(requestedName);
            if (sanitized == null) {
                send(sender, ChatColor.RED + "Please provide a valid file name.");
                return;
            }

            Path parent = currentPath.getParent();
            if (parent == null) {
                send(sender, ChatColor.RED + "Could not determine target directory.");
                return;
            }
            Path targetPath = parent.resolve(sanitized).toAbsolutePath().normalize();
            if (targetPath.equals(currentPath)) {
                send(sender, ChatColor.GRAY + "The file name remains unchanged.");
                return;
            }
            if (Files.exists(targetPath)) {
                send(sender, ChatColor.RED + "A file with that name already exists: " + targetPath.getFileName());
                return;
            }

            boolean wasLoaded = target.isLoaded();
            String pluginName = Optional.ofNullable(target.getName()).orElse("Plugin");

            try {
                if (target.isEnabled()) {
                    pluginLifecycleManager.disablePlugin(pluginName);
                }
                if (target.isLoaded()) {
                    pluginLifecycleManager.unloadPlugin(pluginName);
                }
            } catch (PluginLifecycleException ex) {
                send(sender, ChatColor.RED + "Failed to unload " + pluginName + ": " + ex.getMessage());
                logger.log(Level.WARNING, "Failed to unload plugin for rename " + pluginName, ex);
                return;
            }

            try {
                Files.move(currentPath, targetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                try {
                    Files.move(currentPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException inner) {
                    send(sender, ChatColor.RED + "Rename failed: " + inner.getMessage());
                    logger.log(Level.WARNING, "Failed to rename plugin jar", inner);
                    return;
                }
            } catch (IOException ex) {
                send(sender, ChatColor.RED + "Rename failed: " + ex.getMessage());
                logger.log(Level.WARNING, "Failed to rename plugin jar", ex);
                return;
            }

            List<UpdateSource> matchingSources = updateSourceRegistry.getSources().stream()
                    .filter(source -> matchesPluginSource(source, pluginName, currentPath))
                    .toList();

            for (UpdateSource source : matchingSources) {
                if (updateSourceRegistry.updateSourceFilename(source.getName(), sanitized)) {
                    configuration.set("filenames." + source.getName(), sanitized);
                }
            }
            if (!matchingSources.isEmpty()) {
                plugin.saveConfig();
            }

            pluginLifecycleManager.updateManagedPluginPath(currentPath, targetPath);

            send(sender, ChatColor.GREEN + "Renamed plugin file to " + ChatColor.AQUA + sanitized + ChatColor.GREEN + ".");
            if (wasLoaded) {
                send(sender, ChatColor.YELLOW + "Please reload or restart the server to activate the plugin again.");
            }
        });
    }

    private void clearPendingSelection(CommandSender sender) {
        if (sender == null) {
            return;
        }
        String key = selectionKey(sender);
        pendingSelections.remove(key);
        pendingArchiveSelections.remove(key);
        pendingCompatibilityConfirmations.remove(key);
    }

    private String selectionKey(CommandSender sender) {
        if (sender instanceof Player player) {
            return "player:" + player.getUniqueId();
        }
        String name = Optional.ofNullable(sender.getName()).orElse("console");
        return "sender:" + name.toLowerCase(Locale.ROOT);
    }

    private String assetLabel(AssetSelectionRequiredException.ReleaseAsset asset) {
        if (asset == null) {
            return "Unknown";
        }
        String name = trimToNull(asset.name());
        if (name != null) {
            return name;
        }
        String fromUrl = extractFileName(asset.downloadUrl());
        return fromUrl != null ? fromUrl : asset.downloadUrl();
    }

    private String extractFileName(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String path = Optional.ofNullable(uri.getPath()).orElse("");
            int lastSlash = path.lastIndexOf('/');
            String candidate = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            candidate = candidate.split("\\?")[0];
            return candidate.isBlank() ? null : candidate;
        } catch (Exception e) {
            int queryIndex = url.indexOf('?');
            String sanitized = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
            int lastSlash = sanitized.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash + 1 < sanitized.length()) {
                return sanitized.substring(lastSlash + 1);
            }
            return sanitized.isBlank() ? null : sanitized;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveDefaultFilename(String... candidates) {
        if (candidates == null || candidates.length == 0) {
            return null;
        }

        Set<String> keys = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            keys.add(candidate);
            keys.add(candidate.toLowerCase(Locale.ROOT));
            keys.add(sanitizeKey(candidate));
        }

        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = configuration.getString("filenames." + key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    private List<String> pathSegments(String path) {
        List<String> segments = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return segments;
        }
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String sanitizeKey(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("-+", "-");
        normalized = trimDashes(normalized);
        return normalized.isBlank() ? "source" : normalized;
    }

    private String trimDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    private String toDisplayName(String slug) {
        if (slug == null || slug.isBlank()) {
            return "Plugin";
        }
        String[] parts = slug.replace('-', ' ').replace('_', ' ').split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
            builder.append(' ');
        }
        return builder.toString().trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private record ConfigurationSnapshot(String data) {
    }

    private record PendingSelection(InstallationPlan plan,
                                    List<AssetSelectionRequiredException.ReleaseAsset> assets,
                                    AssetSelectionRequiredException.AssetType assetType,
                                    String releaseTag) {
        private PendingSelection {
            Objects.requireNonNull(plan, "plan");
            assets = List.copyOf(assets);
        }
    }

    private record ArchivePendingSelection(InstallationPlan plan,
                                           List<ArchiveEntry> entries) {
        private ArchivePendingSelection {
            Objects.requireNonNull(plan, "plan");
            entries = List.copyOf(entries);
        }
    }

    private record CompatibilityPending(InstallationPlan plan,
                                        CompatibilityMismatchException compatibility) {
        private CompatibilityPending {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(compatibility, "compatibility");
        }
    }

    static final class CurseforgeProjectPath {
        private final int slugIndex;
        private final String slug;

        CurseforgeProjectPath(int slugIndex, String slug) {
            this.slugIndex = slugIndex;
            this.slug = slug;
        }

        int slugIndex() {
            return slugIndex;
        }

        String slug() {
            return slug;
        }
    }

    static final class OwnerSlug {
        private final String owner;
        private final String slug;

        OwnerSlug(String owner, String slug) {
            this.owner = owner;
            this.slug = slug;
        }

        String owner() {
            return owner;
        }

        String slug() {
            return slug;
        }
    }

    static final class SpigotResource {
        private final long resourceId;
        private final String slug;

        SpigotResource(long resourceId, String slug) {
            this.resourceId = resourceId;
            this.slug = slug;
        }

        long resourceId() {
            return resourceId;
        }

        String slug() {
            return slug;
        }
    }

    private final class InstallationPlan {
        private final String originalUrl;
        private final String providerLabel;
        private final String host;
        private final String fetcherType;
        private final String suggestedName;
        private final String displayName;
        private final TargetDirectory targetDirectory;
        private final Map<String, Object> options;
        private final Set<String> pluginNameCandidates = new LinkedHashSet<>();
        private final String defaultFilename;

        private String sourceName;
        private String filename;
        private String latestVersion;
        private int latestBuild;
        private String downloadUrl;

        InstallationPlan(String originalUrl,
                          String providerLabel,
                          String host,
                          String fetcherType,
                          String suggestedName,
                          String displayName,
                          TargetDirectory targetDirectory,
                          Map<String, Object> options,
                          String defaultFilename) {
            this.originalUrl = originalUrl;
            this.providerLabel = providerLabel;
            this.host = host;
            this.fetcherType = fetcherType;
            this.suggestedName = sanitizeKey(suggestedName);
            this.displayName = displayName;
            this.targetDirectory = targetDirectory;
            this.options = options;
            this.defaultFilename = (defaultFilename == null || defaultFilename.isBlank())
                    ? this.suggestedName + ".jar"
                    : defaultFilename;
            this.sourceName = this.suggestedName;
        }

        public String getOriginalUrl() {
            return originalUrl;
        }

        public String getProvider() {
            return providerLabel;
        }

        public String getFetcherType() {
            return fetcherType;
        }

        public String getSuggestedName() {
            return suggestedName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public TargetDirectory getTargetDirectory() {
            return targetDirectory;
        }

        public Map<String, Object> getOptions() {
            return options;
        }

        public Set<String> getPluginNameCandidates() {
            return pluginNameCandidates;
        }

        public String getDefaultFilename() {
            return defaultFilename;
        }

        public String getSourceName() {
            return sourceName;
        }

        public void setSourceName(String sourceName) {
            this.sourceName = sourceName;
        }

        public void addPluginNameCandidate(String candidate) {
            if (candidate != null && !candidate.isBlank()) {
                pluginNameCandidates.add(candidate);
            }
        }

        public void setInstalledPluginName(String pluginName) {
            if (pluginName != null && !pluginName.isBlank()) {
                options.put("installedPlugin", pluginName);
            }
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public String getFilename() {
            return filename != null ? filename : defaultFilename;
        }

        public void setLatestVersion(String latestVersion) {
            this.latestVersion = latestVersion;
        }

        public void setLatestBuild(int latestBuild) {
            this.latestBuild = latestBuild;
        }

        public void setDownloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public String getLatestVersionInfo() {
            if (latestVersion != null && !latestVersion.isBlank()) {
                return latestVersion;
            }
            return "Build " + latestBuild;
        }

        public Optional<String> getHost() {
            return Optional.ofNullable(host);
        }

        public void addPluginNameCandidate(Collection<String> candidates) {
            if (candidates != null) {
                candidates.forEach(this::addPluginNameCandidate);
            }
        }
    }
}
