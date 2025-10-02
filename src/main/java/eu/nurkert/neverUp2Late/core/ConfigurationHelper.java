package eu.nurkert.neverUp2Late.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Utility helpers for manipulating the plugin configuration in a consistent way.
 */
public final class ConfigurationHelper {

    private ConfigurationHelper() {
    }

    /**
     * Ensures that {@code updates.sources} is backed by a configuration section instead of
     * a legacy list structure. Existing entries are migrated in-place. Callers must save
     * the configuration if they want the migration to persist immediately.
     */
    public static ConfigurationSection ensureSourcesSection(FileConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");

        ConfigurationSection section = configuration.getConfigurationSection("updates.sources");
        if (section != null) {
            return section;
        }

        List<Map<?, ?>> legacyEntries = configuration.getMapList("updates.sources");
        configuration.set("updates.sources", null);
        section = configuration.createSection("updates.sources");

        if (legacyEntries == null || legacyEntries.isEmpty()) {
            return section;
        }

        int unnamedIndex = 1;
        for (Map<?, ?> entry : legacyEntries) {
            if (entry == null) {
                continue;
            }

            String rawName = Objects.toString(entry.get("name"), "source" + unnamedIndex++);
            String key = rawName.trim();
            if (key.isEmpty()) {
                key = "source" + unnamedIndex++;
            }

            // Avoid overwriting entries if keys are duplicated in the legacy format.
            String uniqueKey = key;
            int duplicateIndex = 2;
            while (section.isConfigurationSection(uniqueKey)) {
                uniqueKey = key + "_" + duplicateIndex++;
            }

            ConfigurationSection child = section.createSection(uniqueKey);
            child.set("name", rawName);
            copyIfPresent(child, "type", entry.get("type"));
            copyIfPresent(child, "target", entry.get("target"));
            copyIfPresent(child, "filename", entry.get("filename"));

            Object enabled = entry.get("enabled");
            if (enabled != null) {
                child.set("enabled", parseBoolean(enabled, true));
            }

            Object options = entry.get("options");
            if (options instanceof Map<?, ?> map) {
                ConfigurationSection optionsSection = child.createSection("options");
                for (Map.Entry<?, ?> optionEntry : map.entrySet()) {
                    if (optionEntry.getKey() != null) {
                        optionsSection.set(optionEntry.getKey().toString(), optionEntry.getValue());
                    }
                }
            }
        }

        return section;
    }

    private static void copyIfPresent(ConfigurationSection target, String path, Object value) {
        if (value != null) {
            target.set(path, value);
        }
    }

    private static boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string.trim());
        }
        return defaultValue;
    }
}
