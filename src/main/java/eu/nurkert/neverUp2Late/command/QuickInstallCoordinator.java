package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.fetcher.AssetPatternBuilder;
import eu.nurkert.neverUp2Late.fetcher.GithubReleaseFetcher;
import eu.nurkert.neverUp2Late.fetcher.exception.AssetSelectionRequiredException;
import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.util.ArchiveUtils;
import eu.nurkert.neverUp2Late.util.ArchiveUtils.ArchiveEntry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.entity.Player;

import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleException;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;

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
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QuickInstallCoordinator {

    private final JavaPlugin plugin;
    private final BukkitScheduler scheduler;
    private final FileConfiguration configuration;
    private final UpdateSourceRegistry updateSourceRegistry;
    private final eu.nurkert.neverUp2Late.handlers.UpdateHandler updateHandler;
    private final eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler persistentPluginHandler;
    private final eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository pluginUpdateSettingsRepository;
    private final eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager pluginLifecycleManager;
    private final Logger logger;
    private final String messagePrefix;
    private final Map<String, PendingSelection> pendingSelections = new ConcurrentHashMap<>();
    private final Map<String, ArchivePendingSelection> pendingArchiveSelections = new ConcurrentHashMap<>();
    private final ArtifactDownloader artifactDownloader = new ArtifactDownloader();

    public QuickInstallCoordinator(PluginContext context) {
        this.plugin = context.getPlugin();
        this.scheduler = context.getScheduler();
        this.configuration = context.getConfiguration();
        this.updateSourceRegistry = context.getUpdateSourceRegistry();
        this.updateHandler = context.getUpdateHandler();
        this.persistentPluginHandler = context.getPersistentPluginHandler();
        this.pluginUpdateSettingsRepository = context.getPluginUpdateSettingsRepository();
        this.pluginLifecycleManager = context.getPluginLifecycleManager();
        this.logger = plugin.getLogger();
        this.messagePrefix = ChatColor.GRAY + "[" + ChatColor.AQUA + "nu2l" + ChatColor.GRAY + "] " + ChatColor.RESET;
    }

    public void install(CommandSender sender, String rawUrl) {
        install(sender, rawUrl, null);
    }

    public void installForPlugin(CommandSender sender, String pluginName, String rawUrl) {
        install(sender, rawUrl, pluginName);
    }

    private void install(CommandSender sender, String rawUrl, String forcedPluginName) {
        if (!hasPermission(sender, Permissions.INSTALL)) {
            return;
        }
        String url = rawUrl != null ? rawUrl.trim() : "";
        if (url.isEmpty()) {
            send(sender, ChatColor.RED + "Bitte gib eine gültige URL an.");
            return;
        }

        clearPendingSelection(sender);

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            send(sender, ChatColor.RED + "Die URL ist ungültig: " + e.getMessage());
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
            send(sender, ChatColor.GRAY + "Verknüpfe mit ausgewähltem Plugin: " + forcedPluginName);
        } else {
            detectInstalledPlugin(plan).ifPresent(installed -> {
                plan.setInstalledPluginName(installed);
                send(sender, ChatColor.GRAY + "Verknüpfe mit installiertem Plugin: " + installed);
            });
        }

        send(sender, ChatColor.AQUA + "Quelle erkannt: " + plan.getDisplayName() + " (" + plan.getProvider() + ")");

        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, plan));
    }

    private void prepareAndInstall(CommandSender sender, InstallationPlan plan) {
        send(sender, ChatColor.YELLOW + "Lade Versionsinformationen …");
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
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to prepare installation for " + plan.getDisplayName(), e);
            send(sender, ChatColor.RED + "Fehler beim Laden der Versionsinformationen: " + e.getMessage());
            return;
        }

        if (!ensureArchiveConfiguration(sender, plan, fetcher)) {
            return;
        }

        send(sender, ChatColor.GREEN + "Neueste Version: " + plan.getLatestVersionInfo());

        scheduler.runTask(plugin, () -> finalizeInstallation(sender, plan));
    }

    public void handleAssetSelection(CommandSender sender, String selectionInput) {
        if (!hasPermission(sender, Permissions.INSTALL)) {
            return;
        }
        if (selectionInput == null || selectionInput.isBlank()) {
            send(sender, ChatColor.RED + "Bitte gib die Nummer der gewünschten Datei an.");
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
            send(sender, ChatColor.RED + "Bitte gib eine gültige Nummer an.");
            return;
        }

        if (index < 1 || index > pending.assets().size()) {
            send(sender, ChatColor.RED + "Ungültige Auswahl. Bitte wähle eine Zahl zwischen 1 und " + pending.assets().size() + ".");
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

        send(sender, ChatColor.GREEN + "Verwende Asset \"" + assetName + "\". Regex wurde automatisch erstellt.");
        if (asset.archive()) {
            send(sender, ChatColor.GOLD + "Hinweis: Das ausgewählte Asset ist ein Archiv. NU2L extrahiert automatisch die passende JAR-Datei.");
        }

        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, pending.plan()));
    }

    private void handleArchiveSelection(CommandSender sender, String selectionInput, String key) {
        ArchivePendingSelection pending = pendingArchiveSelections.get(key);
        if (pending == null) {
            send(sender, ChatColor.RED + "Es gibt keine offene Auswahl.");
            return;
        }

        int index;
        try {
            index = Integer.parseInt(selectionInput.trim());
        } catch (NumberFormatException ex) {
            send(sender, ChatColor.RED + "Bitte gib eine gültige Nummer an.");
            return;
        }

        if (index < 1 || index > pending.entries().size()) {
            send(sender, ChatColor.RED + "Ungültige Auswahl. Bitte wähle eine Zahl zwischen 1 und " + pending.entries().size() + ".");
            return;
        }

        ArchiveEntry selected = pending.entries().get(index - 1);
        List<String> candidateNames = pending.entries().stream().map(ArchiveEntry::fullPath).toList();
        String pattern = AssetPatternBuilder.build(selected.fullPath(), candidateNames);

        pending.plan().getOptions().put("archiveEntryPattern", pattern);
        pending.plan().setFilename(extractFileNameForArchive(selected));
        pendingArchiveSelections.remove(key);

        send(sender, ChatColor.GREEN + "Verwende JAR \"" + selected.fullPath() + "\" aus dem Archiv.");
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
            send(sender, ChatColor.RED + "Fehler: Archiv-Download-URL fehlt.");
            return false;
        }

        List<ArchiveEntry> entries;
        try {
            entries = inspectArchive(downloadUrl);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to inspect archive for " + plan.getDisplayName(), e);
            send(sender, ChatColor.RED + "Fehler beim Auswerten des Archivs: " + e.getMessage());
            return false;
        }

        if (entries.isEmpty()) {
            send(sender, ChatColor.RED + "Das Archiv enthält keine JAR-Dateien.");
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
            send(sender, ChatColor.GRAY + "Finde JAR im Archiv: " + entries.get(0).fullPath());
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

        send(sender, ChatColor.GOLD + "Das Archiv enthält mehrere JAR-Dateien:");
        for (int i = 0; i < entries.size(); i++) {
            ArchiveEntry entry = entries.get(i);
            send(sender, ChatColor.GRAY + String.valueOf(i + 1) + ChatColor.DARK_GRAY + ". "
                    + ChatColor.AQUA + entry.fullPath());
        }
        send(sender, ChatColor.YELLOW + "Bitte wähle mit /nu2l select <Nummer> die gewünschte Datei aus.");
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
            case ARCHIVE -> "Archive";
            case JAR -> "JAR-Dateien";
            default -> "Assets";
        };

        send(sender, ChatColor.GOLD + "Mehrere " + typeLabel + " gefunden für " + plan.getDisplayName()
                + " (" + selection.getReleaseTag() + "):");
        List<AssetSelectionRequiredException.ReleaseAsset> assets = selection.getAssets();
        for (int i = 0; i < assets.size(); i++) {
            AssetSelectionRequiredException.ReleaseAsset asset = assets.get(i);
            String label = assetLabel(asset);
            String suffix = asset.archive() ? ChatColor.YELLOW + " (Archiv)" : "";
            send(sender, ChatColor.GRAY + String.valueOf(i + 1) + ChatColor.DARK_GRAY + ". "
                    + ChatColor.AQUA + label + suffix);
        }
        send(sender, ChatColor.YELLOW + "Bitte wähle mit /nu2l select <Nummer> das gewünschte Asset aus.");
        if (selection.getAssetType() == AssetSelectionRequiredException.AssetType.ARCHIVE
                || assets.stream().anyMatch(AssetSelectionRequiredException.ReleaseAsset::archive)) {
            send(sender, ChatColor.GOLD + "Hinweis: Archive werden automatisch entpackt, bitte wähle die gewünschte Datei.");
        }
    }

    private void finalizeInstallation(CommandSender sender, InstallationPlan plan) {
        if (updateSourceRegistry.hasSource(plan.getSourceName())) {
            send(sender, ChatColor.YELLOW + "Quelle existiert bereits, starte Aktualisierung …");
            UpdateSource existing = updateSourceRegistry.findSource(plan.getSourceName()).orElse(null);
            if (existing != null) {
                updateHandler.runJobNow(existing, sender);
            }
            return;
        }

        writeConfiguration(plan);

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
            send(sender, ChatColor.RED + "Konnte Quelle nicht registrieren: " + e.getMessage());
            return;
        }

        send(sender, ChatColor.GREEN + "Installation wird gestartet …");
        updateHandler.runJobNow(source, sender);
    }

    private void writeConfiguration(InstallationPlan plan) {
        ConfigurationSection section = configuration.getConfigurationSection("updates.sources");
        if (section != null && !section.getKeys(false).isEmpty()) {
            if (section.contains(plan.getSourceName())) {
                section.set(plan.getSourceName(), null);
            }
            ConfigurationSection newSection = section.createSection(plan.getSourceName());
            populateSection(newSection, plan);
        } else {
            List<Map<?, ?>> entries = new ArrayList<>(configuration.getMapList("updates.sources"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", plan.getSourceName());
            entry.put("type", plan.getFetcherType());
            entry.put("target", plan.getTargetDirectory().name());
            entry.put("filename", plan.getFilename());
            if (!plan.getOptions().isEmpty()) {
                entry.put("options", new LinkedHashMap<>(plan.getOptions()));
            }
            entries.removeIf(map -> plan.getSourceName().equalsIgnoreCase(Objects.toString(map.get("name"), "")));
            entries.add(entry);
            configuration.set("updates.sources", entries);
        }
        plugin.saveConfig();
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
                if (website != null && plan.getHost().isPresent() && website.contains(plan.getHost().get())) {
                    return Optional.of(entry.getValue().getName());
                }
            }
        }

        return Optional.empty();
    }

    private InstallationPlan analyse(URI uri, String originalUrl) {
        String host = Optional.ofNullable(uri.getHost()).map(String::toLowerCase).orElse("");
        if (host.contains("hangar.papermc")) {
            return buildHangarPlan(uri, originalUrl, host);
        }
        if (host.contains("modrinth.com")) {
            return buildModrinthPlan(uri, originalUrl, host);
        }
        if (host.contains("github.com")) {
            return buildGithubPlan(uri, originalUrl, host);
        }
        if (uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).contains("/job/")) {
            return buildJenkinsPlan(uri, originalUrl, host);
        }
        throw new IllegalArgumentException("Diese URL wird aktuell nicht unterstützt.");
    }

    private InstallationPlan buildHangarPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        OwnerSlug ownerSlug = extractOwnerAndSlug(segments, List.of("plugins", "plugin", "project"))
                .orElseThrow(() -> new IllegalArgumentException("Hangar-URL muss das Format /Owner/Projekt haben."));
        String owner = ownerSlug.owner();
        String slug = ownerSlug.slug();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("project", owner + "/" + slug);
        options.put("platform", "PAPER");

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
                .orElseThrow(() -> new IllegalArgumentException("Modrinth-URL konnte nicht ausgewertet werden."));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("project", slug);
        options.put("loaders", List.of("paper", "spigot"));

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

    static Optional<String> extractModrinthSlug(List<String> segments) {
        String slug = null;
        boolean expectSlug = false;

        for (String rawSegment : segments) {
            String segment = decode(rawSegment);
            if (segment.isBlank()) {
                continue;
            }

            if (expectSlug) {
                slug = segment;
                break;
            }

            if (segment.equalsIgnoreCase("plugin") || segment.equalsIgnoreCase("project")) {
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
                .orElseThrow(() -> new IllegalArgumentException("GitHub-URL muss das Format /Owner/Repository haben."));
        String owner = ownerSlug.owner();
        String repository = ownerSlug.slug();

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("owner", owner);
        options.put("repository", repository);
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
            throw new IllegalArgumentException("Jenkins-URL muss /job/<Name> enthalten.");
        }

        String jobPath = String.join("/", jobSegments);
        String baseUrl = buildJenkinsBaseUrl(uri, segments);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("baseUrl", baseUrl);
        options.put("job", jobPath);
        options.put("artifactPattern", "(?i).*\\.jar$");

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
        try {
            URI uri = new URI(downloadUrl);
            String path = Optional.ofNullable(uri.getPath()).orElse("");
            int lastSlash = path.lastIndexOf('/');
            String candidate = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            candidate = candidate.isBlank() ? fallback : candidate;
            candidate = candidate.split("\\?")[0];
            if (candidate.isBlank()) {
                candidate = fallback;
            }
            if (!candidate.contains(".")) {
                candidate = candidate + ".jar";
            }
            return candidate;
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to parse filename from " + downloadUrl, e);
            return fallback;
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
        send(sender, ChatColor.RED + "Dir fehlt die Berechtigung (" + permission + ").");
        return false;
    }

    public void removeManagedPlugin(CommandSender sender, ManagedPlugin target) {
        if (target == null) {
            send(sender, ChatColor.RED + "Unbekanntes Plugin – Aktion abgebrochen.");
            return;
        }
        if (!hasPermission(sender, Permissions.GUI_MANAGE_REMOVE)) {
            return;
        }
        if (pluginLifecycleManager == null) {
            send(sender, ChatColor.RED + "Die Plugin-Verwaltung ist deaktiviert; Entfernen nicht möglich.");
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
                send(sender, ChatColor.RED + "Fehler beim Entladen von " + pluginName + ": " + ex.getMessage());
                logger.log(Level.WARNING, "Failed to unload plugin " + pluginName, ex);
                return;
            }

            if (jarPath != null) {
                try {
                    Files.deleteIfExists(jarPath);
                } catch (IOException ex) {
                    send(sender, ChatColor.RED + "Konnte Datei nicht löschen: " + jarPath.getFileName());
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

            send(sender, ChatColor.GREEN + "Plugin " + ChatColor.AQUA + pluginName + ChatColor.GREEN + " wurde entfernt.");
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
            send(sender, ChatColor.RED + "Die Plugin-Verwaltung ist deaktiviert; Entfernen nicht möglich.");
            return;
        }
        ManagedPlugin target = pluginLifecycleManager.findByName(pluginName).orElse(null);
        if (target == null) {
            send(sender, ChatColor.RED + "Plugin " + pluginName + " wurde nicht gefunden.");
            return;
        }
        removeManagedPlugin(sender, target);
    }

    public void renameManagedPlugin(CommandSender sender, ManagedPlugin target, String requestedName) {
        if (target == null) {
            send(sender, ChatColor.RED + "Unbekanntes Plugin – Aktion abgebrochen.");
            return;
        }
        if (!hasPermission(sender, Permissions.GUI_MANAGE_RENAME)) {
            return;
        }
        if (pluginLifecycleManager == null) {
            send(sender, ChatColor.RED + "Die Plugin-Verwaltung ist deaktiviert; Umbenennung nicht möglich.");
            return;
        }

        scheduler.runTask(plugin, () -> {
            Path currentPath = target.getPath();
            if (currentPath == null) {
                send(sender, ChatColor.RED + "Es konnte kein Dateipfad ermittelt werden.");
                return;
            }

            String sanitized = sanitizeFilename(requestedName);
            if (sanitized == null) {
                send(sender, ChatColor.RED + "Bitte gib einen gültigen Dateinamen an.");
                return;
            }

            Path parent = currentPath.getParent();
            if (parent == null) {
                send(sender, ChatColor.RED + "Konnte Zielverzeichnis nicht bestimmen.");
                return;
            }
            Path targetPath = parent.resolve(sanitized).toAbsolutePath().normalize();
            if (targetPath.equals(currentPath)) {
                send(sender, ChatColor.GRAY + "Der Dateiname bleibt unverändert.");
                return;
            }
            if (Files.exists(targetPath)) {
                send(sender, ChatColor.RED + "Eine Datei mit diesem Namen existiert bereits: " + targetPath.getFileName());
                return;
            }

            boolean wasEnabled = target.isEnabled();
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
                send(sender, ChatColor.RED + "Fehler beim Entladen von " + pluginName + ": " + ex.getMessage());
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
                    send(sender, ChatColor.RED + "Umbenennen fehlgeschlagen: " + inner.getMessage());
                    logger.log(Level.WARNING, "Failed to rename plugin jar", inner);
                    return;
                }
            } catch (IOException ex) {
                send(sender, ChatColor.RED + "Umbenennen fehlgeschlagen: " + ex.getMessage());
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

            if (wasLoaded) {
                try {
                    pluginLifecycleManager.loadPlugin(targetPath);
                    if (wasEnabled) {
                        pluginLifecycleManager.enablePlugin(pluginName);
                    }
                } catch (PluginLifecycleException ex) {
                    send(sender, ChatColor.YELLOW + "Plugin wurde umbenannt, konnte aber nicht neu geladen werden: " + ex.getMessage());
                    logger.log(Level.WARNING, "Failed to reload plugin after rename", ex);
                    return;
                }
            }

            send(sender, ChatColor.GREEN + "Plugin-Datei in " + ChatColor.AQUA + sanitized + ChatColor.GREEN + " umbenannt.");
        });
    }

    private String sanitizeFilename(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String sanitized = trimmed.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (!sanitized.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            sanitized = sanitized + ".jar";
        }
        return sanitized;
    }

    private void clearPendingSelection(CommandSender sender) {
        if (sender == null) {
            return;
        }
        String key = selectionKey(sender);
        pendingSelections.remove(key);
        pendingArchiveSelections.remove(key);
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
            return "Unbekannt";
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
