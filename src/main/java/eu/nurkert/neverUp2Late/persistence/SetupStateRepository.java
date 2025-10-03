package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists the state of the interactive initial setup flow.
 */
public class SetupStateRepository {

    private static final String FILE_NAME = "setup-state.yml";
    private static final String ROOT_NODE = "state";
    private static final String PHASE_NODE = "phase";

    private final File dataFolder;
    private final Logger logger;
    private final File stateFile;

    private FileConfiguration configuration;

    public enum SetupPhase {
        UNINITIALISED,
        CONFIGURED,
        DOWNLOADS_TRIGGERED,
        COMPLETED;

        public static SetupPhase parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNINITIALISED;
            }
            try {
                return SetupPhase.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return UNINITIALISED;
            }
        }
    }

    public SetupStateRepository(File dataFolder, Logger logger) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stateFile = new File(dataFolder, FILE_NAME);
        initialise();
    }

    public static SetupStateRepository forPlugin(JavaPlugin plugin) {
        return new SetupStateRepository(plugin.getDataFolder(), plugin.getLogger());
    }

    private void initialise() {
        ensureDataFolderExists();
        ensureStateFileExists();
        configuration = YamlConfiguration.loadConfiguration(stateFile);
        if (!configuration.isConfigurationSection(ROOT_NODE)) {
            configuration.createSection(ROOT_NODE);
            configuration.set(ROOT_NODE + "." + PHASE_NODE, SetupPhase.UNINITIALISED.name());
            saveInternal();
        }
    }

    private void ensureDataFolderExists() {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.log(Level.WARNING, "Could not create plugin data folder at {0}", dataFolder.getAbsolutePath());
        }
    }

    private void ensureStateFileExists() {
        if (!stateFile.exists()) {
            try {
                if (!stateFile.createNewFile()) {
                    logger.log(Level.WARNING, "Unable to create {0}", stateFile.getAbsolutePath());
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to create " + FILE_NAME, e);
            }
        }
    }

    public synchronized SetupPhase getPhase() {
        String value = configuration.getString(ROOT_NODE + "." + PHASE_NODE);
        return SetupPhase.parse(value);
    }

    public synchronized void setPhase(SetupPhase phase) {
        if (phase == null) {
            phase = SetupPhase.UNINITIALISED;
        }
        configuration.set(ROOT_NODE + "." + PHASE_NODE, phase.name());
        saveInternal();
    }

    private void saveInternal() {
        try {
            configuration.save(stateFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to persist setup state", e);
        }
    }
}
