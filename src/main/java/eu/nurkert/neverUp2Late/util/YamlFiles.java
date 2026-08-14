package eu.nurkert.neverUp2Late.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loading helper for the small YAML state files this plugin keeps.
 *
 * <p>{@link YamlConfiguration#loadConfiguration(File)} answers an unreadable
 * file with an <em>empty</em> configuration. Every caller here then writes that
 * empty configuration straight back, so a single truncated write - a power cut,
 * a full disk - quietly erased which plugins had automatic updates switched off
 * and which builds were already installed. A file we could not parse is moved
 * aside instead, so the operator still has it.</p>
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    public static YamlConfiguration loadOrQuarantine(File file, Logger logger) {
        YamlConfiguration configuration = new YamlConfiguration();
        if (file == null || !file.isFile() || file.length() == 0L) {
            return configuration;
        }
        try {
            configuration.load(file);
            return configuration;
        } catch (IOException | InvalidConfigurationException ex) {
            quarantine(file, logger, ex);
            return new YamlConfiguration();
        }
    }

    private static void quarantine(File file, Logger logger, Exception cause) {
        File backup = new File(file.getParentFile(),
                file.getName() + ".corrupt-" + Instant.now().toEpochMilli());
        boolean moved = file.renameTo(backup);
        if (logger == null) {
            return;
        }
        if (moved) {
            logger.log(Level.WARNING,
                    "Could not read {0} ({1}). It was kept as {2} and NeverUp2Late starts from an empty state.",
                    new Object[]{file.getName(), cause.getMessage(), backup.getName()});
        } else {
            logger.log(Level.SEVERE,
                    "Could not read {0} ({1}) and failed to move it aside. Please check the file by hand.",
                    new Object[]{file.getName(), cause.getMessage()});
        }
    }
}
