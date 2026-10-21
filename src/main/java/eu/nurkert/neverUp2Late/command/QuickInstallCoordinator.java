package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.util.Theme;
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
import eu.nurkert.neverUp2Late.util.ChatMessages;
import eu.nurkert.neverUp2Late.util.ArchiveUtils;
import eu.nurkert.neverUp2Late.util.ArchiveUtils.ArchiveEntry;
import eu.nurkert.neverUp2Late.net.HttpClient;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.entity.Player;

import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleException;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class QuickInstallCoordinator {

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
    private final ManagedPluginActions managedPluginActions;

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
        this.messagePrefix = Theme.PREFIX;
        this.ignoreCompatibilityWarnings = configuration.getBoolean("quickInstall.ignoreCompatibilityWarnings", false);
        this.managedPluginActions = new ManagedPluginActions(
                plugin,
                scheduler,
                configuration,
                updateSourceRegistry,
                persistentPluginHandler,
                pluginUpdateSettingsRepository,
                pluginLifecycleManager,
                artifactDownloader,
                logger,
                this::send,
                this::hasPermission);
    }

    private InstallationPlanner planner() {
        return new InstallationPlanner(this, configuration, httpClient, logger, ignoreCompatibilityWarnings);
    }

    private SourceDeduplicator sourceDeduplicator() {
        return new SourceDeduplicator(
                updateSourceRegistry,
                configuration,
                configurationLock,
                plugin,
                logger,
                this::resolveDestination,
                this::send);
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
            send(sender, Theme.ERROR + "Please specify the update source to roll back.");
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
            send(sender, Theme.ERROR + "Please provide a valid URL.");
            return;
        }

        clearPendingSelection(sender);

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            send(sender, Theme.ERROR + "The URL is invalid: " + e.getMessage());
            return;
        }

        // analyse() reaches out over the network for some providers (a
        // CurseForge project page lookup, for one). Doing that on the server
        // thread freezes every player for as long as the request takes.
        scheduler.runTaskAsynchronously(plugin, () -> {
            InstallationPlan plan;
            try {
                plan = analyse(uri, url);
            } catch (IllegalArgumentException e) {
                send(sender, Theme.ERROR + e.getMessage());
                return;
            }

            // Reading the plugin registry belongs on the server thread.
            runOnMainThread(() -> {
                plan.setSourceName(ensureUniqueName(plan.getSuggestedName()));
                if (forcedPluginName != null && !forcedPluginName.isBlank()) {
                    plan.setInstalledPluginName(forcedPluginName);
                    send(sender, Theme.MUTED + "Linking with selected plugin: " + forcedPluginName);
                } else {
                    detectInstalledPlugin(plan).ifPresent(installed -> {
                        plan.setInstalledPluginName(installed);
                        send(sender, Theme.MUTED + "Linking with installed plugin: " + installed);
                    });
                }

                send(sender, Theme.ACCENT + "Detected source: " + plan.getDisplayName()
                        + " (" + plan.getProvider() + ")");

                scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, plan));
            });
        });
    }

    private void executeRollback(CommandSender sender, String sourceName) {
        Optional<UpdateSource> optionalSource = updateSourceRegistry.findSource(sourceName);
        if (optionalSource.isEmpty()) {
            send(sender, Theme.ERROR + "No update source named " + sourceName + " is registered.");
            return;
        }

        UpdateSource source = optionalSource.get();
        Path destination = resolveDestination(source);
        if (destination == null) {
            send(sender, Theme.ERROR + "Cannot determine the destination file for " + source.getName() + ".");
            return;
        }

        try {
            Optional<ArtifactDownloader.RestorationResult> restoration = artifactDownloader.restorePreviousBackup(
                    destination,
                    source.getInstalledPluginName(),
                    source.getName()
            );

            if (restoration.isEmpty()) {
                send(sender, Theme.ERROR + "No backups available for " + source.getName() + ".");
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

            send(sender, Theme.SUCCESS + "Restored backup " + Theme.ACCENT + backupName
                    + Theme.SUCCESS + " for " + Theme.ACCENT + source.getName()
                    + Theme.SUCCESS + " → " + Theme.ACCENT + destinationName + Theme.SUCCESS + ".");

            triggerLifecycleAfterRollback(sender, source, destination);
        } catch (IOException ex) {
            logger.log(Level.WARNING,
                    "Failed to restore backup for {0}: {1}",
                    new Object[]{source.getName(), ex.getMessage()});
            send(sender, Theme.ERROR + "Failed to restore backup: " + ex.getMessage());
        }
    }

    private void triggerLifecycleAfterRollback(CommandSender sender, UpdateSource source, Path destination) {
        if (pluginLifecycleManager == null || source.getTargetDirectory() != TargetDirectory.PLUGINS) {
            return;
        }

        scheduler.runTask(plugin, () -> {
            String preferredName = InstallationPlanner.trimToNull(source.getInstalledPluginName());
            try {
                boolean reloaded = preferredName != null
                        ? pluginLifecycleManager.reloadPlugin(preferredName)
                        : pluginLifecycleManager.reloadPlugin(destination);
                if (reloaded) {
                    String name = preferredName != null ? preferredName
                            : Optional.ofNullable(destination.getFileName()).map(Path::toString).orElse(destination.toString());
                    send(sender, Theme.SUCCESS + "Reloaded plugin " + Theme.ACCENT + name
                            + Theme.SUCCESS + " after rollback.");
                } else {
                    send(sender, Theme.WARNING + "Rollback completed, but the plugin could not be reloaded automatically.");
                }
            } catch (PluginLifecycleException ex) {
                logger.log(Level.WARNING,
                        "Failed to reload plugin after rollback for {0}: {1}",
                        new Object[]{source.getName(), ex.getMessage()});
                send(sender, Theme.ERROR + "Rollback succeeded, but reloading the plugin failed: " + ex.getMessage());
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
        sourceDeduplicator().deduplicateExistingSources(sender);
    }

    private Map<Path, Integer> computePathUsage() {
        return sourceDeduplicator().computePathUsage();
    }

    private Set<String> cleanupRedundantSources(CommandSender sender,
                                                UpdateSource primary,
                                                List<UpdateSource> redundant,
                                                Map<Path, Integer> usage) {
        return sourceDeduplicator().cleanupRedundantSources(sender, primary, redundant, usage);
    }

    private List<UpdateSource> findConflictingSources(InstallationPlan plan) {
        return sourceDeduplicator().findConflictingSources(plan);
    }

    private boolean describesSameArtifact(UpdateSource source, InstallationPlan plan) {
        return sourceDeduplicator().describesSameArtifact(source, plan);
    }

    private boolean fetcherMatchesPlan(UpdateSource source, InstallationPlan plan) {
        return sourceDeduplicator().fetcherMatchesPlan(source, plan);
    }

    private void removeConflictingSources(CommandSender sender, List<UpdateSource> conflicts) {
        sourceDeduplicator().removeConflictingSources(sender, conflicts);
    }

    private void prepareAndInstall(CommandSender sender, InstallationPlan plan) {
        send(sender, Theme.WARNING + "Loading version information…");
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
            send(sender, Theme.ERROR + "Failed to load version information: " + e.getMessage());
            return;
        }

        if (!ensureArchiveConfiguration(sender, plan, fetcher)) {
            return;
        }

        send(sender, Theme.SUCCESS + "Latest version: " + plan.getLatestVersionInfo());

        scheduler.runTask(plugin, () -> finalizeInstallation(sender, plan));
    }

    public void handleAssetSelection(CommandSender sender, String selectionInput) {
        if (!hasPermission(sender, Permissions.INSTALL)) {
            return;
        }
        if (selectionInput == null || selectionInput.isBlank()) {
            send(sender, Theme.ERROR + "Please specify the number of the desired file.");
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
            send(sender, Theme.ERROR + "Please enter a valid number.");
            return;
        }

        if (index < 1 || index > pending.assets().size()) {
            send(sender, Theme.ERROR + "Invalid selection. Please choose a number between 1 and " + pending.assets().size() + ".");
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

        send(sender, Theme.SUCCESS + "Using asset \"" + assetName + "\". Regex created automatically.");
        if (asset.archive()) {
            send(sender, Theme.HEADING + "Note: The selected asset is an archive. NU2L will automatically extract the matching jar file.");
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
            send(sender, Theme.ERROR + "There is no pending compatibility warning.");
            return;
        }

        if (!ignoreWarning) {
            send(sender, Theme.WARNING + "Installation cancelled. Compatibility warning not ignored.");
            return;
        }

        pending.plan().getOptions().put("ignoreCompatibilityWarnings", true);
        send(sender, Theme.WARNING + "Ignoring compatibility warning and retrying with the latest version…");
        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, pending.plan()));
    }

    private void handleArchiveSelection(CommandSender sender, String selectionInput, String key) {
        ArchivePendingSelection pending = pendingArchiveSelections.get(key);
        if (pending == null) {
            send(sender, Theme.ERROR + "There is no pending selection.");
            return;
        }

        int index;
        try {
            index = Integer.parseInt(selectionInput.trim());
        } catch (NumberFormatException ex) {
            send(sender, Theme.ERROR + "Please enter a valid number.");
            return;
        }

        if (index < 1 || index > pending.entries().size()) {
            send(sender, Theme.ERROR + "Invalid selection. Please choose a number between 1 and " + pending.entries().size() + ".");
            return;
        }

        ArchiveEntry selected = pending.entries().get(index - 1);
        List<String> candidateNames = pending.entries().stream().map(ArchiveEntry::fullPath).toList();
        String pattern = AssetPatternBuilder.build(selected.fullPath(), candidateNames);

        pending.plan().getOptions().put("archiveEntryPattern", pattern);
        pending.plan().setFilename(extractFileNameForArchive(selected));
        pendingArchiveSelections.remove(key);

        send(sender, Theme.SUCCESS + "Using jar \"" + selected.fullPath() + "\" from the archive.");
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
            send(sender, Theme.ERROR + "Error: archive download URL missing.");
            return false;
        }

        List<ArchiveEntry> entries;
        try {
            entries = inspectArchive(downloadUrl);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to inspect archive for " + plan.getDisplayName(), e);
            send(sender, Theme.ERROR + "Failed to inspect archive: " + e.getMessage());
            return false;
        }

        if (entries.isEmpty()) {
            send(sender, Theme.ERROR + "The archive does not contain any jar files.");
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
            send(sender, Theme.MUTED + "Found a jar in the archive: " + entries.get(0).fullPath());
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

        send(sender, Theme.HEADING + "The archive contains multiple jar files:");
        for (int i = 0; i < entries.size(); i++) {
            ArchiveEntry entry = entries.get(i);
            sendChoice(sender, i + 1,
                    Theme.MUTED + String.valueOf(i + 1) + Theme.SUBTLE + ". "
                            + Theme.ACCENT + entry.fullPath());
        }
        send(sender, Theme.WARNING + "Click the file you want, or use /nu2l select <number>.");
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
            case JAR -> "jar files";
            default -> "assets";
        };

        send(sender, Theme.HEADING + "Found multiple " + typeLabel + " for " + plan.getDisplayName()
                + " (" + selection.getReleaseTag() + "):");
        List<AssetSelectionRequiredException.ReleaseAsset> assets = selection.getAssets();
        for (int i = 0; i < assets.size(); i++) {
            AssetSelectionRequiredException.ReleaseAsset asset = assets.get(i);
            String label = assetLabel(asset);
            String suffix = asset.archive() ? Theme.WARNING + " (archive)" : "";
            sendChoice(sender, i + 1,
                    Theme.MUTED + String.valueOf(i + 1) + Theme.SUBTLE + ". "
                            + Theme.ACCENT + label + suffix);
        }
        send(sender, Theme.WARNING + "Click the file you want, or use /nu2l select <number>.");
        if (selection.getAssetType() == AssetSelectionRequiredException.AssetType.ARCHIVE
                || assets.stream().anyMatch(AssetSelectionRequiredException.ReleaseAsset::archive)) {
            send(sender, Theme.HEADING + "Note: Archives are extracted automatically; please choose the desired file.");
        }
    }

    private void requestCompatibilityOverride(CommandSender sender,
                                              InstallationPlan plan,
                                              CompatibilityMismatchException compatibility) {
        String key = selectionKey(sender);
        pendingCompatibilityConfirmations.put(key, new CompatibilityPending(plan, compatibility));

        send(sender, Theme.HEADING + "No compatible version was advertised for " + Theme.ACCENT
                + plan.getDisplayName() + Theme.HEADING + ".");
        send(sender, Theme.WARNING + compatibility.getMessage());

        String serverVersion = InstallationPlanner.trimToNull(compatibility.getServerVersion());
        if (serverVersion != null) {
            send(sender, Theme.MUTED + "Your server reports Minecraft version " + Theme.ACCENT
                    + serverVersion + Theme.MUTED + ".");
        }

        List<String> available = compatibility.getAvailableVersions();
        if (!available.isEmpty()) {
            String joined = String.join(Theme.MUTED + ", " + Theme.ACCENT, available);
            send(sender, Theme.MUTED + "Listed versions: " + Theme.ACCENT + joined);
        }

        send(sender, Theme.WARNING + "Use /nu2l ignore to install the latest version anyway or /nu2l cancel to abort.");
    }

    private void finalizeInstallation(CommandSender sender, InstallationPlan plan) {
        String relinkedSource = null;
        deduplicateExistingSources(sender);
        enforcePluginPathSanity(plan, sender);

        List<UpdateSource> conflicts = findConflictingSources(plan);
        if (!conflicts.isEmpty()) {
            Optional<UpdateSource> primaryMatch = conflicts.stream()
                    .filter(source -> fetcherMatchesPlan(source, plan))
                    .findFirst();
            if (primaryMatch.isPresent()) {
                UpdateSource primary = primaryMatch.get();
                List<UpdateSource> redundant = new ArrayList<>(conflicts);
                redundant.remove(primary);
                if (!redundant.isEmpty()) {
                    cleanupRedundantSources(sender, primary, redundant, computePathUsage());
                }
                if (describesSameArtifact(primary, plan)) {
                    send(sender, Theme.WARNING + "This link is already configured as "
                            + Theme.ACCENT + primary.getName() + Theme.WARNING + "; checking for a new version…");
                    updateHandler.runJobNow(primary, sender);
                    return;
                }
                // Same provider, different target. Matching on the fetcher class
                // alone made every GitHub link look like every other one, so the
                // new URL was dropped and the old source re-run - while the
                // message claimed an update was starting. Re-link instead, by
                // letting the plan replace that entry.
                send(sender, Theme.WARNING + "Pointing " + Theme.ACCENT + primary.getName()
                        + Theme.WARNING + " at the new link.");
                // Reuse the entry: applyPlanToConfiguration replaces a source of
                // the same name. The old registration is dropped further down,
                // once the new configuration is safely on disk - unregistering
                // here would lose the source entirely if the write then failed.
                plan.setSourceName(primary.getName());
                relinkedSource = primary.getName();
            } else {
                removeConflictingSources(sender, conflicts);
            }
        }

        ensurePreferredSourceName(plan);

        if (updateSourceRegistry.hasSource(plan.getSourceName())) {
            send(sender, Theme.WARNING + "Source already exists, starting update…");
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

        if (relinkedSource != null) {
            updateSourceRegistry.unregisterSource(relinkedSource);
            // The recorded build and version belong to the artifact this source
            // used to point at. Keeping them would make the first check of the
            // new target look like "already installed".
            persistentPluginHandler.removePluginInfo(relinkedSource);
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
            // Rebuild the registry from the restored file, so a re-link that
            // failed halfway leaves the previous source in place rather than
            // nothing at all.
            updateSourceRegistry.reload();
            send(sender, Theme.ERROR + "Could not register source: " + e.getMessage());
            return;
        }

        send(sender, Theme.SUCCESS + "Registered update source " + Theme.ACCENT + source.getName()
                + Theme.SUCCESS + " → " + Theme.ACCENT + source.getFilename() + Theme.SUCCESS + ".");
        send(sender, Theme.SUCCESS + "Starting installation…");
        updateHandler.runJobNow(source, sender);
    }

    private void enforcePluginPathSanity(InstallationPlan plan, CommandSender sender) {
        if (pluginLifecycleManager == null) {
            // pluginLifecycle.autoManage is off. There is no plugin registry to
            // reconcile against, and dereferencing it here aborted the install
            // with an NPE after the source had already been unregistered.
            return;
        }
        String installedName = (String) plan.getOptions().get("installedPlugin");
        
        // Auto-detect installed plugin if not yet set by reading destination file if it exists
        if (installedName == null || installedName.isBlank()) {
            Path dest = resolveDestination(new UpdateSource(plan.getSourceName(), null, plan.getTargetDirectory(), plan.getFilename(), null));
            if (dest != null && Files.exists(dest)) {
                ArchiveUtils.getPluginInfo(dest).ifPresent(info -> {
                    plan.setInstalledPluginName(info.name());
                    logger.log(Level.INFO, "Auto-detected installed plugin name for orphan file {0}: {1}", 
                            new Object[]{plan.getFilename(), info.name()});
                });
            }
        }
        
        // Re-read after potential auto-detection
        installedName = (String) plan.getOptions().get("installedPlugin");
        if (installedName == null || installedName.isBlank()) {
            return;
        }

        final String finalInstalledName = installedName;
        pluginLifecycleManager.findByName(finalInstalledName).ifPresent(managed -> {
            Path currentJarPath = managed.getPath();
            if (currentJarPath == null) {
                return;
            }

            String currentFilename = currentJarPath.getFileName().toString();
            String planFilename = plan.getFilename();

            if (!currentFilename.equalsIgnoreCase(planFilename)) {
                plan.setFilename(currentFilename);
                logger.log(Level.INFO, "Synchronized plan filename for {0} to existing file: {1}",
                        new Object[]{finalInstalledName, currentFilename});
            }

            // Aggressive Cleanup: Delete ANY other JAR file that contains this plugin name
            pluginLifecycleManager.deleteAllDuplicates(finalInstalledName, currentJarPath);
            
            // Also cleanup the plan filename if it's currently occupied by an orphan.
            Path potentialDuplicate = currentJarPath.getParent().resolve(planFilename);
            try {
                if (Files.exists(potentialDuplicate) && !Files.isSameFile(potentialDuplicate, currentJarPath)) {
                    // Only ever delete a jar that really is another copy of THIS
                    // plugin. The plan filename is derived from a URL and can
                    // easily collide with an unrelated plugin's jar, which used
                    // to be deleted without ever being looked at.
                    String occupantName = ArchiveUtils.getPluginInfo(potentialDuplicate)
                            .map(ArchiveUtils.PluginInfo::name)
                            .orElse(null);
                    if (occupantName != null && occupantName.equalsIgnoreCase(finalInstalledName)) {
                        Files.delete(potentialDuplicate);
                        logger.log(Level.INFO, "Deleted duplicate jar found during installation: {0}",
                                potentialDuplicate);
                    } else {
                        logger.log(Level.WARNING,
                                "Keeping {0}: it belongs to {1}, not to {2}.",
                                new Object[]{potentialDuplicate.getFileName(),
                                        occupantName == null ? "an unknown plugin" : occupantName,
                                        finalInstalledName});
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to check or delete duplicate jar: " + potentialDuplicate, e);
            }
        });
    }

    private ConfigurationSnapshot persistSourceConfiguration(InstallationPlan plan, CommandSender sender) {
        synchronized (configurationLock) {
            ConfigurationSnapshot snapshot = createSnapshot();
            if (snapshot == null) {
                send(sender, Theme.ERROR + "Could not create a configuration backup. Aborting.");
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
                send(sender, Theme.ERROR + "Could not write configuration: " + ex.getMessage());
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

        List<Map<?, ?>> entries = new ArrayList<>();
        for (Map<?, ?> original : configuration.getMapList("updates.sources")) {
            entries.add(deepCopyMap(original));
        }

        entries.removeIf(map -> plan.getSourceName().equalsIgnoreCase(Objects.toString(map.get("name"), "")));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", plan.getSourceName());
        entry.put("type", plan.getFetcherType());
        entry.put("target", plan.getTargetDirectory().name());
        entry.put("filename", plan.getFilename());
        if (!plan.getOptions().isEmpty()) {
            entry.put("options", new LinkedHashMap<>(plan.getOptions()));
        }
        entries.add(entry);
        configuration.set("updates.sources", entries);
    }

    private Map<String, Object> deepCopyMap(Map<?, ?> original) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = deepCopyMap((Map<?, ?>) value);
            } else if (value instanceof ConfigurationSection section) {
                value = deepCopyMap(section.getValues(false));
            }
            copy.put(entry.getKey().toString(), value);
        }
        return copy;
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
        return planner().detectInstalledPlugin(plan);
    }

    private InstallationPlan analyse(URI uri, String originalUrl) {
        return planner().analyse(uri, originalUrl);
    }

    private String determineFilename(String downloadUrl, String fallback) {
        return planner().determineFilename(downloadUrl, fallback);
    }

    private String ensureUniqueName(String base) {
        String candidate = base;
        int counter = 1;
        while (updateSourceRegistry.hasSource(candidate)) {
            candidate = base + "-" + counter++;
        }
        return candidate;
    }

    private void ensurePreferredSourceName(InstallationPlan plan) {
        if (plan == null || updateSourceRegistry == null) {
            return;
        }

        String suggested = plan.getSuggestedName();
        if (suggested == null || suggested.isBlank()) {
            return;
        }

        String current = plan.getSourceName();
        if (current == null || current.isBlank()
                || current.equals(suggested)
                || current.startsWith(suggested + "-")) {
            String preferred = ensureUniqueName(suggested);
            if (!preferred.equals(current)) {
                plan.setSourceName(preferred);
            }
        }
    }

    /** One entry of a list to choose from; clicking it selects entry {@code number}. */
    private void sendChoice(CommandSender sender, int number, String message) {
        if (sender == null) {
            return;
        }
        String text = messagePrefix + message;
        String command = "/nu2l select " + number;
        if (!runOnMainThread(() -> ChatMessages.sendChoice(sender, text, number, command))
                && sender instanceof org.bukkit.command.ConsoleCommandSender) {
            sender.sendMessage(text);
        }
    }

    private void send(CommandSender sender, String message) {
        if (sender == null) {
            return;
        }
        String text = messagePrefix + message;
        if (runOnMainThread(() -> sender.sendMessage(text))) {
            return;
        }
        // Nothing can be scheduled any more (the server is going down). Talking
        // to a Player off the server thread is not safe, so only the console -
        // which is - gets the message directly; for anyone else it goes to the
        // log rather than being lost silently.
        if (sender instanceof org.bukkit.command.ConsoleCommandSender) {
            sender.sendMessage(text);
        } else {
            logger.log(Level.FINE, "Dropped a message during shutdown: {0}", message);
        }
    }

    /**
     * Runs {@code action} on the server thread, or right away if already there.
     *
     * <p>Bukkit answers a scheduling attempt on a disabled plugin with an
     * exception. Unguarded, that turned a shutdown during an install into a
     * stack trace and aborted the remaining steps - after the jar on disk had
     * already been replaced.</p>
     *
     * @return {@code false} if nothing could be scheduled
     */
    private boolean runOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return false;
        }
        if (plugin.getServer().isPrimaryThread()) {
            action.run();
            return true;
        }
        try {
            scheduler.runTask(plugin, action);
            return true;
        } catch (org.bukkit.plugin.IllegalPluginAccessException | IllegalStateException ex) {
            logger.log(Level.FINE, "Skipped a main thread task because the server is shutting down", ex);
            return false;
        }
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender == null || permission == null || permission.isBlank()) {
            return true;
        }
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, Theme.ERROR + "You do not have the required permission (" + permission + ").");
        return false;
    }

    public void removeManagedPlugin(CommandSender sender, ManagedPlugin target) {
        managedPluginActions.removeManagedPlugin(sender, target);
    }

    public void removePlugin(CommandSender sender, String pluginName) {
        managedPluginActions.removePlugin(sender, pluginName);
    }

    public void disableAutomaticUpdates(CommandSender sender, ManagedPlugin target) {
        managedPluginActions.disableAutomaticUpdates(sender, target);
    }

    public void renameManagedPlugin(CommandSender sender, ManagedPlugin target, String requestedName) {
        managedPluginActions.renameManagedPlugin(sender, target, requestedName);
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
        String name = InstallationPlanner.trimToNull(asset.name());
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

    final class InstallationPlan {
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
            this.suggestedName = InstallationPlanner.sanitizeKey(suggestedName);
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
