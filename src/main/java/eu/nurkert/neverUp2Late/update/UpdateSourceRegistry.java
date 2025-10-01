package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.MemoryConfiguration;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry that creates {@link UpdateFetcher} instances from configuration entries.
 */
public class UpdateSourceRegistry {

    private static final String DEFAULT_FETCHER_PACKAGE = "eu.nurkert.neverUp2Late.fetcher";

    private final Logger logger;
    private final FileConfiguration configuration;
    private final boolean ignoreUnstableGlobal;
    private final List<UpdateSource> sources = new ArrayList<>();

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

            String filename = determineFilename(name, asString(entry.get("filename")));
            if (filename == null) {
                logger.log(Level.WARNING,
                        "No filename defined for update source {0}. Set updates.sources[].filename or filenames.{0}.",
                        name);
                continue;
            }

            TargetDirectory targetDirectory = parseTargetDirectory(asString(entry.get("target")), name);
            ConfigurationSection optionsSection = createOptionsSection(entry.get("options"));

            try {
                UpdateFetcher fetcher = instantiateFetcher(type, optionsSection);
                sources.add(new UpdateSource(name, fetcher, targetDirectory, filename));
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Unable to create update fetcher for source {0}: {1}",
                        new Object[]{name, e.getMessage()});
                logger.log(Level.FINE, "Fetcher creation failed", e);
            }
        }
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

        return Arrays.asList(paper, geyser);
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

    public enum TargetDirectory {
        SERVER,
        PLUGINS
    }

    public static class UpdateSource {
        private final String name;
        private final UpdateFetcher fetcher;
        private final TargetDirectory targetDirectory;
        private final String filename;

        public UpdateSource(String name, UpdateFetcher fetcher, TargetDirectory targetDirectory, String filename) {
            this.name = name;
            this.fetcher = fetcher;
            this.targetDirectory = targetDirectory;
            this.filename = filename;
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
    }
}
