package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.fetcher.AssetPatternBuilder;
import eu.nurkert.neverUp2Late.fetcher.exception.AssetSelectionRequiredException;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import java.util.logging.Level;
import java.util.logging.Logger;

public class QuickInstallCoordinator {

    private final JavaPlugin plugin;
    private final BukkitScheduler scheduler;
    private final FileConfiguration configuration;
    private final UpdateSourceRegistry updateSourceRegistry;
    private final eu.nurkert.neverUp2Late.handlers.UpdateHandler updateHandler;
    private final Logger logger;
    private final String messagePrefix;
    private final Map<String, PendingSelection> pendingSelections = new ConcurrentHashMap<>();

    public QuickInstallCoordinator(PluginContext context) {
        this.plugin = context.getPlugin();
        this.scheduler = context.getScheduler();
        this.configuration = context.getConfiguration();
        this.updateSourceRegistry = context.getUpdateSourceRegistry();
        this.updateHandler = context.getUpdateHandler();
        this.logger = plugin.getLogger();
        this.messagePrefix = ChatColor.GRAY + "[" + ChatColor.AQUA + "nu2l" + ChatColor.GRAY + "] " + ChatColor.RESET;
    }

    public void install(CommandSender sender, String rawUrl) {
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
        detectInstalledPlugin(plan).ifPresent(installed -> {
            plan.setInstalledPluginName(installed);
            send(sender, ChatColor.GRAY + "Verknüpfe mit installiertem Plugin: " + installed);
        });

        send(sender, ChatColor.AQUA + "Quelle erkannt: " + plan.getDisplayName() + " (" + plan.getProvider() + ")");

        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, plan));
    }

    private void prepareAndInstall(CommandSender sender, InstallationPlan plan) {
        send(sender, ChatColor.YELLOW + "Lade Versionsinformationen …");
        try {
            var fetcher = updateSourceRegistry.createFetcher(plan.getFetcherType(), plan.getOptions());
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

        send(sender, ChatColor.GREEN + "Neueste Version: " + plan.getLatestVersionInfo());

        scheduler.runTask(plugin, () -> finalizeInstallation(sender, plan));
    }

    public void handleAssetSelection(CommandSender sender, String selectionInput) {
        if (selectionInput == null || selectionInput.isBlank()) {
            send(sender, ChatColor.RED + "Bitte gib die Nummer der gewünschten Datei an.");
            return;
        }

        String key = selectionKey(sender);
        PendingSelection pending = pendingSelections.get(key);
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

        if (index < 1 || index > pending.assets().size()) {
            send(sender, ChatColor.RED + "Ungültige Auswahl. Bitte wähle eine Zahl zwischen 1 und " + pending.assets().size() + ".");
            return;
        }

        AssetSelectionRequiredException.ReleaseAsset asset = pending.assets().get(index - 1);
        String assetName = assetLabel(asset);
        String pattern = AssetPatternBuilder.build(assetName);

        pending.plan().getOptions().put("assetPattern", pattern);
        pendingSelections.remove(key);

        send(sender, ChatColor.GREEN + "Verwende Asset \"" + assetName + "\". Regex wurde automatisch erstellt.");
        if (asset.archive()) {
            send(sender, ChatColor.GOLD + "Hinweis: Das ausgewählte Asset ist ein Archiv und muss nach dem Download manuell entpackt werden.");
        }

        scheduler.runTaskAsynchronously(plugin, () -> prepareAndInstall(sender, pending.plan()));
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
            send(sender, ChatColor.GOLD + "Hinweis: Archive müssen nach dem Download manuell entpackt werden.");
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
        if (segments.size() < 2) {
            throw new IllegalArgumentException("Hangar-URL muss das Format /Owner/Projekt haben.");
        }
        String owner = decode(segments.get(0));
        String slug = decode(segments.get(1));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("project", owner + "/" + slug);
        options.put("platform", "PAPER");

        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                "Hangar",
                host,
                "hangar",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options
        );
        plan.addPluginNameCandidate(owner);
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        return plan;
    }

    private InstallationPlan buildModrinthPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        String slug = segments.stream()
                .map(this::decode)
                .filter(segment -> !segment.equalsIgnoreCase("plugin") && !segment.equalsIgnoreCase("project"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalArgumentException("Modrinth-URL konnte nicht ausgewertet werden."));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("project", slug);
        options.put("loaders", List.of("paper", "spigot"));

        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                "Modrinth",
                host,
                "modrinth",
                slug,
                toDisplayName(slug),
                TargetDirectory.PLUGINS,
                options
        );
        plan.addPluginNameCandidate(slug);
        plan.addPluginNameCandidate(toDisplayName(slug));
        return plan;
    }

    private InstallationPlan buildGithubPlan(URI uri, String originalUrl, String host) {
        List<String> segments = pathSegments(uri.getPath());
        if (segments.size() < 2) {
            throw new IllegalArgumentException("GitHub-URL muss das Format /Owner/Repository haben.");
        }
        String owner = decode(segments.get(0));
        String repository = decode(segments.get(1));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("owner", owner);
        options.put("repository", repository);
        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                "GitHub Releases",
                host,
                "githubRelease",
                repository,
                toDisplayName(repository),
                TargetDirectory.PLUGINS,
                options
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
        InstallationPlan plan = new InstallationPlan(
                originalUrl,
                providerLabel,
                host,
                "jenkins",
                sanitizeKey(displayName),
                displayName,
                TargetDirectory.PLUGINS,
                options
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

    private void clearPendingSelection(CommandSender sender) {
        if (sender == null) {
            return;
        }
        pendingSelections.remove(selectionKey(sender));
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private String decode(String value) {
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
                          Map<String, Object> options) {
            this.originalUrl = originalUrl;
            this.providerLabel = providerLabel;
            this.host = host;
            this.fetcherType = fetcherType;
            this.suggestedName = sanitizeKey(suggestedName);
            this.displayName = displayName;
            this.targetDirectory = targetDirectory;
            this.options = options;
            this.defaultFilename = this.suggestedName + ".jar";
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
