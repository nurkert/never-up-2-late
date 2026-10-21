package eu.nurkert.neverUp2Late.command;

import eu.nurkert.neverUp2Late.command.QuickInstallCoordinator.InstallationPlan;
import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Keeps the source registry free of entries that compete for the same plugin.
 *
 * <p>Two sources that point at the same installed plugin or the same jar
 * would overwrite each other on every update run. Before a quick install is
 * registered, this finds such duplicates and conflicts, decides which entry
 * survives and unregisters the rest - the jars themselves are left alone.</p>
 */
final class SourceDeduplicator {

    private final UpdateSourceRegistry updateSourceRegistry;
    private final FileConfiguration configuration;
    private final Object configurationLock;
    private final JavaPlugin plugin;
    private final Logger logger;
    private final Function<UpdateSource, Path> destinationResolver;
    private final BiConsumer<CommandSender, String> messenger;

    SourceDeduplicator(UpdateSourceRegistry updateSourceRegistry,
                       FileConfiguration configuration,
                       Object configurationLock,
                       JavaPlugin plugin,
                       Logger logger,
                       Function<UpdateSource, Path> destinationResolver,
                       BiConsumer<CommandSender, String> messenger) {
        this.updateSourceRegistry = updateSourceRegistry;
        this.configuration = configuration;
        this.configurationLock = configurationLock;
        this.plugin = plugin;
        this.logger = logger;
        this.destinationResolver = destinationResolver;
        this.messenger = messenger;
    }

    void deduplicateExistingSources(CommandSender sender) {
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
            Path destination = normalizePath(destinationResolver.apply(source));
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

    Map<Path, Integer> computePathUsage() {
        Map<Path, Integer> usage = new HashMap<>();
        if (updateSourceRegistry == null) {
            return usage;
        }
        for (UpdateSource source : updateSourceRegistry.getSources()) {
            Path destination = normalizePath(destinationResolver.apply(source));
            if (destination != null) {
                usage.merge(destination, 1, Integer::sum);
            }
        }
        return usage;
    }

    Set<String> cleanupRedundantSources(CommandSender sender,
                                        UpdateSource primary,
                                        List<UpdateSource> redundant,
                                        Map<Path, Integer> usage) {
        if (updateSourceRegistry == null || primary == null || redundant == null || redundant.isEmpty()) {
            return Set.of();
        }

        Set<String> removed = new LinkedHashSet<>();
        Path primaryPath = normalizePath(destinationResolver.apply(primary));

        for (UpdateSource duplicate : redundant) {
            if (!updateSourceRegistry.unregisterSource(duplicate.getName())) {
                continue;
            }
            removed.add(duplicate.getName());
            Path duplicatePath = normalizePath(destinationResolver.apply(duplicate));
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
                // Previously: delete file. Now we keep it to prevent data loss.
                logger.log(Level.FINE, "Duplicate artifact {0} kept for safety after source cleanup.", duplicatePath);
            }
        }

        if (!removed.isEmpty() && sender != null) {
            messenger.accept(sender, ChatColor.YELLOW + "Removed duplicate update sources: " + String.join(", ", removed));
        }

        return removed;
    }

    List<UpdateSource> findConflictingSources(InstallationPlan plan) {
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

    /**
     * Whether an existing source and a new plan point at the same thing.
     *
     * <p>The fetcher class only says which provider is involved; what identifies
     * the artifact lives in the options - owner and repository, the project
     * slug, the resource id. Options that merely describe handling are ignored,
     * so re-installing the same link does not count as a change.</p>
     */
    boolean describesSameArtifact(UpdateSource source, InstallationPlan plan) {
        if (!fetcherMatchesPlan(source, plan)) {
            return false;
        }
        return identifyingOptions(configuredOptions(source.getName()))
                .equals(identifyingOptions(plan.getOptions()));
    }

    private static final Set<String> NON_IDENTIFYING_OPTIONS =
            Set.of("installedplugin", "ignorecompatibilitywarnings", "_ignoreunstabledefault",
                    "preferredgameversions", "maxgameversion");

    private Map<String, String> identifyingOptions(Map<String, Object> options) {
        Map<String, String> identity = new LinkedHashMap<>();
        if (options == null) {
            return identity;
        }
        options.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            if (NON_IDENTIFYING_OPTIONS.contains(normalized)) {
                return;
            }
            identity.put(normalized, String.valueOf(value));
        });
        return identity;
    }

    /** Reads the options a source is configured with, in either config layout. */
    private Map<String, Object> configuredOptions(String sourceName) {
        synchronized (configurationLock) {
            ConfigurationSection section = configuration.getConfigurationSection("updates.sources");
            if (section != null && !section.getKeys(false).isEmpty()) {
                // Sources are matched case-insensitively everywhere else, so a
                // key that differs only in case must not read as "no options" -
                // that would make every install look like a re-link.
                ConfigurationSection entry = section.getKeys(false).stream()
                        .filter(key -> key.equalsIgnoreCase(sourceName))
                        .findFirst()
                        .map(section::getConfigurationSection)
                        .orElse(null);
                ConfigurationSection options = entry == null ? null : entry.getConfigurationSection("options");
                return options == null ? Map.of() : options.getValues(true);
            }
            for (Map<?, ?> raw : configuration.getMapList("updates.sources")) {
                if (!sourceName.equalsIgnoreCase(Objects.toString(raw.get("name"), ""))) {
                    continue;
                }
                Object options = raw.get("options");
                if (options instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    return copy;
                }
                if (options instanceof ConfigurationSection configured) {
                    return configured.getValues(true);
                }
            }
            return Map.of();
        }
    }

    boolean fetcherMatchesPlan(UpdateSource source, InstallationPlan plan) {
        if (source == null || plan == null) {
            return false;
        }

        UpdateFetcher fetcher = source.getFetcher();
        if (fetcher == null) {
            return false;
        }

        String type = plan.getFetcherType();
        if (type == null || type.isBlank()) {
            return false;
        }

        String normalizedType = type.trim();
        Class<?> fetcherClass = fetcher.getClass();

        if (normalizedType.contains(".")) {
            return fetcherClass.getName().equalsIgnoreCase(normalizedType);
        }

        String target = normalizedType.toLowerCase(Locale.ROOT);
        String simpleName = fetcherClass.getSimpleName().toLowerCase(Locale.ROOT);
        if (simpleName.equals(target) || simpleName.equals(target + "fetcher")) {
            return true;
        }

        String fullName = fetcherClass.getName().toLowerCase(Locale.ROOT);
        return fullName.endsWith('.' + target) || fullName.endsWith('.' + target + "fetcher");
    }

    void removeConflictingSources(CommandSender sender, List<UpdateSource> conflicts) {
        if (updateSourceRegistry == null || conflicts == null || conflicts.isEmpty()) {
            return;
        }

        Set<String> removed = new LinkedHashSet<>();

        for (UpdateSource conflict : conflicts) {
            if (conflict == null) {
                continue;
            }
            String name = conflict.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (updateSourceRegistry.unregisterSource(name)) {
                removed.add(name);
                if (logger != null) {
                    logger.log(Level.INFO,
                            "Removed conflicting update source {0} before applying new quick install plan.",
                            name);
                }
            }
        }

        if (removed.isEmpty()) {
            return;
        }

        if (sender != null) {
            messenger.accept(sender, ChatColor.YELLOW + "Removed conflicting update sources: " + String.join(", ", removed));
        }

        saveConfigQuietly();
    }

    private void saveConfigQuietly() {
        if (plugin == null) {
            return;
        }
        try {
            plugin.saveConfig();
        } catch (RuntimeException ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to save configuration after removing conflicting sources", ex);
            }
        }
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
}
