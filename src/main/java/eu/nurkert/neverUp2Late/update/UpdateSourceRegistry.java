package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Registry that creates {@link UpdateFetcher} instances from configuration entries.
 */
public class UpdateSourceRegistry {

    private static final String DEFAULT_FETCHER_PACKAGE = "eu.nurkert.neverUp2Late.fetcher";
    private static final String OPTION_IGNORE_UNSTABLE_DEFAULT = "_ignoreUnstableDefault";

    private final Logger logger;
    private final FileConfiguration configuration;
    private final boolean ignoreUnstableGlobal;
    private final CopyOnWriteArrayList<UpdateSource> sources = new CopyOnWriteArrayList<>();

    public UpdateSourceRegistry(Logger logger, FileConfiguration configuration) {
        this.logger = logger;
        this.configuration = configuration;
        this.ignoreUnstableGlobal = configuration.getBoolean(
                "updates.ignoreUnstable",
                configuration.getBoolean("ignoreUnstable", true)
        );

        loadSources();
    }

    public List<UpdateSource> getSources() {
        return Collections.unmodifiableList(sources);
    }

    public synchronized void reload() {
        sources.clear();
        loadSources();
    }

    public Optional<UpdateSource> findSource(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim();
        return sources.stream()
                .filter(source -> source.getName().equalsIgnoreCase(normalized))
                .findFirst();
    }

    public boolean hasSource(String name) {
        return findSource(name).isPresent();
    }

    public boolean unregisterSource(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        boolean removed = sources.removeIf(source -> source.getName().equalsIgnoreCase(name.trim()));
        if (removed) {
            removeSourceFromConfiguration(name.trim());
        }
        return removed;
    }

    public boolean updateSourceFilename(String name, String newFilename) {
        if (name == null || name.isBlank() || newFilename == null || newFilename.isBlank()) {
            return false;
        }
        String trimmedName = name.trim();
        String filename = newFilename.trim();
        for (int i = 0; i < sources.size(); i++) {
            UpdateSource source = sources.get(i);
            if (source.getName().equalsIgnoreCase(trimmedName)) {
                UpdateSource replacement = new UpdateSource(
                        source.getName(),
                        source.getFetcher(),
                        source.getTargetDirectory(),
                        filename,
                        source.getInstalledPluginName());
                sources.set(i, replacement);
                updateSourceFilenameInConfiguration(trimmedName, filename);
                return true;
            }
        }
        return false;
    }

    public UpdateFetcher createFetcher(String type, Map<String, Object> options) throws Exception {
        ConfigurationSection section = prepareOptionsSection(createOptionsSection(options));
        return instantiateFetcher(type, section);
    }

    public UpdateSource registerDynamicSource(String name,
                                              String type,
                                              TargetDirectory targetDirectory,
                                              String filename,
                                              Map<String, Object> options) throws Exception {
        ConfigurationSection optionsSection = prepareOptionsSection(createOptionsSection(options));
        UpdateFetcher fetcher = instantiateFetcher(type, optionsSection);
        String installedPlugin = extractInstalledPluginName(optionsSection);
        UpdateSource source = new UpdateSource(name, fetcher, targetDirectory, filename, installedPlugin);
        sources.add(source);
        return source;
    }

    private void loadSources() {
        List<Map<?, ?>> configuredSources = configuration.getMapList("updates.sources");
        ConfigurationSection sourcesSection = configuration.getConfigurationSection("updates.sources");

        if ((configuredSources == null || configuredSources.isEmpty()) && sourcesSection != null) {
            configuredSources = new ArrayList<>();
            for (String key : sourcesSection.getKeys(false)) {
                ConfigurationSection sourceSection = sourcesSection.getConfigurationSection(key);
                if (sourceSection == null) {
                    continue;
                }

                Map<String, Object> sourceMap = new HashMap<>();
                sourceMap.put("name", sourceSection.getString("name", key));
                sourceMap.put("type", sourceSection.getString("type"));
                sourceMap.put("target", sourceSection.getString("target"));
                if (sourceSection.contains("filename")) {
                    sourceMap.put("filename", sourceSection.getString("filename"));
                }
                ConfigurationSection optionsSection = sourceSection.getConfigurationSection("options");
                if (optionsSection != null) {
                    sourceMap.put("options", optionsSection.getValues(true));
                }
                configuredSources.add(sourceMap);
            }
        }

        if (configuredSources == null || configuredSources.isEmpty()) {
            logger.log(Level.INFO,
                    "No update sources configured under updates.sources; applying legacy defaults for Paper and Geyser.");
            configuredSources = createLegacyDefaults();
        }

        for (Map<?, ?> entry : configuredSources) {
            if (entry == null) {
                continue;
            }

            String name = asString(entry.get("name"));
            if (name == null || name.isBlank()) {
                logger.log(Level.WARNING, "Encountered update source without a name; skipping entry: {0}", entry);
                continue;
            }

            String type = asString(entry.get("type"));
            if (type == null || type.isBlank()) {
                logger.log(Level.WARNING, "Update source {0} is missing a type; skipping.", name);
                continue;
            }

            boolean enabled = parseEnabled(entry.get("enabled"));
            if (!enabled) {
                logger.log(Level.FINE, "Update source {0} is disabled via configuration; skipping.", name);
                continue;
            }

            String filename = determineFilename(name, asString(entry.get("filename")));
            if (filename == null) {
                logger.log(Level.WARNING,
                        "No filename defined for update source {0}. Set updates.sources[].filename or filenames.{0}.",
                        name);
                continue;
            }

            TargetDirectory targetDirectory = parseTargetDirectory(asString(entry.get("target")), name);
            ConfigurationSection optionsSection = prepareOptionsSection(createOptionsSection(entry.get("options")));

            try {
                UpdateFetcher fetcher = instantiateFetcher(type, optionsSection);
                String installedPlugin = extractInstalledPluginName(optionsSection);
                sources.add(new UpdateSource(name, fetcher, targetDirectory, filename, installedPlugin));
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Unable to create update fetcher for source {0}: {1}",
                        new Object[]{name, e.getMessage()});
                logger.log(Level.FINE, "Fetcher creation failed", e);
            }
        }
    }

    private boolean parseEnabled(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private List<Map<?, ?>> createLegacyDefaults() {
        Map<String, Object> paper = new HashMap<>();
        paper.put("name", "paper");
        paper.put("type", "paper");
        paper.put("target", TargetDirectory.SERVER.name());

        Map<String, Object> geyser = new HashMap<>();
        geyser.put("name", "geyser");
        geyser.put("type", "geyser");
        geyser.put("target", TargetDirectory.PLUGINS.name());

        Map<String, Object> self = new HashMap<>();
        self.put("name", "neverUp2Late");
        self.put("type", "githubRelease");
        self.put("target", TargetDirectory.PLUGINS.name());
        self.put("filename", "NeverUp2Late.jar");
        Map<String, Object> selfOptions = new HashMap<>();
        selfOptions.put("owner", "nurkert");
        selfOptions.put("repository", "never-up-2-late");
        selfOptions.put("installedPlugin", "NeverUp2Late");
        self.put("options", selfOptions);

        return Arrays.asList(paper, geyser, self);
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private TargetDirectory parseTargetDirectory(String targetValue, String sourceName) {
        if (targetValue == null || targetValue.isBlank()) {
            return TargetDirectory.PLUGINS;
        }

        try {
            return TargetDirectory.valueOf(targetValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.log(Level.WARNING,
                    "Unknown target '{0}' for update source {1}. Falling back to plugins directory.",
                    new Object[]{targetValue, sourceName});
            return TargetDirectory.PLUGINS;
        }
    }

    private String determineFilename(String name, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }

        String configured = configuration.getString("filenames." + name);
        return (configured == null || configured.isBlank()) ? null : configured;
    }

    private String extractInstalledPluginName(ConfigurationSection optionsSection) {
        if (optionsSection == null) {
            return null;
        }
        String value = optionsSection.getString("installedPlugin");
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ConfigurationSection createOptionsSection(Object rawOptions) {
        if (!(rawOptions instanceof Map<?, ?> optionsMap)) {
            return null;
        }

        MemoryConfiguration memoryConfiguration = new MemoryConfiguration();
        for (Map.Entry<?, ?> optionEntry : optionsMap.entrySet()) {
            if (optionEntry.getKey() != null) {
                memoryConfiguration.set(optionEntry.getKey().toString(), optionEntry.getValue());
            }
        }
        return memoryConfiguration;
    }

    private ConfigurationSection prepareOptionsSection(ConfigurationSection optionsSection) {
        ConfigurationSection result = optionsSection != null ? optionsSection : new MemoryConfiguration();
        if (!result.contains(OPTION_IGNORE_UNSTABLE_DEFAULT)) {
            result.set(OPTION_IGNORE_UNSTABLE_DEFAULT, ignoreUnstableGlobal);
        }
        return result;
    }

    private UpdateFetcher instantiateFetcher(String type, ConfigurationSection optionsSection) throws Exception {
        String className = resolveClassName(type);
        Class<?> rawClass = Class.forName(className);

        if (!UpdateFetcher.class.isAssignableFrom(rawClass)) {
            throw new IllegalArgumentException(className + " does not implement UpdateFetcher");
        }

        @SuppressWarnings("unchecked")
        Class<? extends UpdateFetcher> fetcherClass = (Class<? extends UpdateFetcher>) rawClass;

        UpdateFetcher fetcher = tryInstantiateWithConfiguration(fetcherClass, optionsSection);
        if (fetcher != null) {
            return fetcher;
        }

        fetcher = tryInstantiateWithBoolean(fetcherClass);
        if (fetcher != null) {
            return fetcher;
        }

        Constructor<? extends UpdateFetcher> constructor = fetcherClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private UpdateFetcher tryInstantiateWithConfiguration(Class<? extends UpdateFetcher> fetcherClass,
                                                          ConfigurationSection optionsSection) {
        List<Constructor<?>> constructors = Arrays.asList(fetcherClass.getDeclaredConstructors());

        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 1 && ConfigurationSection.class.isAssignableFrom(parameterTypes[0])) {
                try {
                    constructor.setAccessible(true);
                    ConfigurationSection sectionToUse = optionsSection != null
                            ? optionsSection
                            : new MemoryConfiguration();
                    return (UpdateFetcher) constructor.newInstance(sectionToUse);
                } catch (ReflectiveOperationException e) {
                    logger.log(Level.FINE,
                            "Failed to instantiate {0} with ConfigurationSection constructor",
                            fetcherClass.getName());
                }
            } else if (parameterTypes.length == 1 && FileConfiguration.class.isAssignableFrom(parameterTypes[0])) {
                try {
                    constructor.setAccessible(true);
                    return (UpdateFetcher) constructor.newInstance(configuration);
                } catch (ReflectiveOperationException e) {
                    logger.log(Level.FINE,
                            "Failed to instantiate {0} with FileConfiguration constructor",
                            fetcherClass.getName());
                }
            }
        }

        return null;
    }

    private UpdateFetcher tryInstantiateWithBoolean(Class<? extends UpdateFetcher> fetcherClass) {
        try {
            Constructor<? extends UpdateFetcher> constructor = fetcherClass.getDeclaredConstructor(boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(ignoreUnstableGlobal);
        } catch (NoSuchMethodException ignored) {
            // No boolean constructor available
        } catch (ReflectiveOperationException e) {
            logger.log(Level.FINE,
                    "Failed to instantiate {0} with boolean constructor",
                    fetcherClass.getName());
        }

        try {
            Constructor<? extends UpdateFetcher> constructor = fetcherClass.getDeclaredConstructor(Boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(ignoreUnstableGlobal);
        } catch (NoSuchMethodException ignored) {
            // No Boolean constructor available
        } catch (ReflectiveOperationException e) {
            logger.log(Level.FINE,
                    "Failed to instantiate {0} with Boolean constructor",
                    fetcherClass.getName());
        }

        return null;
    }

    private String resolveClassName(String type) {
        String trimmed = type.trim();
        if (trimmed.contains(".")) {
            return trimmed;
        }

        if (Character.isUpperCase(trimmed.charAt(0))) {
            return DEFAULT_FETCHER_PACKAGE + "." + trimmed;
        }

        String pascalCase = toPascalCase(trimmed);
        if (!pascalCase.endsWith("Fetcher")) {
            pascalCase = pascalCase + "Fetcher";
        }
        return DEFAULT_FETCHER_PACKAGE + "." + pascalCase;
    }

    private String toPascalCase(String value) {
        String[] parts = value.split("[^a-zA-Z0-9]+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private void removeSourceFromConfiguration(String name) {
        ConfigurationSection section = configuration.getConfigurationSection("updates.sources");
        if (section != null && !section.getKeys(false).isEmpty()) {
            section.set(name, null);
            return;
        }

        List<Map<?, ?>> entries = new ArrayList<>();
        for (Map<?, ?> original : configuration.getMapList("updates.sources")) {
            entries.add(deepCopyMap(original));
        }

        boolean modified = entries.removeIf(map -> name.equalsIgnoreCase(asString(map.get("name"))));
        if (modified) {
            configuration.set("updates.sources", entries);
        }
    }

    private void updateSourceFilenameInConfiguration(String name, String filename) {
        ConfigurationSection section = configuration.getConfigurationSection("updates.sources");
        if (section != null && !section.getKeys(false).isEmpty()) {
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry != null) {
                entry.set("filename", filename);
            }
            return;
        }

        List<Map<?, ?>> entries = new ArrayList<>();
        boolean modified = false;

        for (Map<?, ?> original : configuration.getMapList("updates.sources")) {
            Map<String, Object> mutable = deepCopyMap(original);
            if (name.equalsIgnoreCase(asString(mutable.get("name")))) {
                mutable.put("filename", filename);
                modified = true;
            }
            entries.add(mutable);
        }

        if (modified) {
            configuration.set("updates.sources", entries);
        }
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

    public enum TargetDirectory {
        SERVER,
        PLUGINS
    }

    public static class UpdateSource {
        private final String name;
        private final UpdateFetcher fetcher;
        private final TargetDirectory targetDirectory;
        private final String filename;
        private final String installedPluginName;

        public UpdateSource(String name,
                            UpdateFetcher fetcher,
                            TargetDirectory targetDirectory,
                            String filename,
                            String installedPluginName) {
            this.name = name;
            this.fetcher = fetcher;
            this.targetDirectory = targetDirectory;
            this.filename = filename;
            this.installedPluginName = installedPluginName;
        }

        public String getName() {
            return name;
        }

        public UpdateFetcher getFetcher() {
            return fetcher;
        }

        public TargetDirectory getTargetDirectory() {
            return targetDirectory;
        }

        public String getFilename() {
            return filename;
        }

        public String getInstalledPluginName() {
            return installedPluginName;
        }
    }
}
