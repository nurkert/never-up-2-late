package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RestartCooldownRepository {

    private static final String FILE_NAME = "restart-state.yml";
    private static final String LAST_RESTART_NODE = "lastRestartTime";

    private final File stateFile;
    private final Logger logger;
    private FileConfiguration configuration;

    public RestartCooldownRepository(File dataFolder, Logger logger) {
        this.logger = logger;
        this.stateFile = new File(dataFolder, FILE_NAME);
        initialise(dataFolder);
    }

    private void initialise(File dataFolder) {
        ensureDataFolderExists(dataFolder);
        ensureStateFileExists();
        configuration = YamlConfiguration.loadConfiguration(stateFile);
    }

    private void ensureDataFolderExists(File dataFolder) {
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

    public synchronized long getLastRestartTime() {
        return configuration.getLong(LAST_RESTART_NODE, 0L);
    }

    public synchronized void saveLastRestartTime(long timestamp) {
        configuration.set(LAST_RESTART_NODE, timestamp);
        try {
            configuration.save(stateFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to persist last restart time", e);
        }
    }
}
