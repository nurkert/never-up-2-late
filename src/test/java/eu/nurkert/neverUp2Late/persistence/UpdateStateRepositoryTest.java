package eu.nurkert.neverUp2Late.persistence;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class UpdateStateRepositoryTest {

    private final Logger logger = Logger.getLogger("test");

    @Test
    void savesAndLoadsLatestBuild(@TempDir Path tempDir) {
        UpdateStateRepository repository = new UpdateStateRepository(tempDir.toFile(), logger);

        repository.saveLatestBuild("paper", 123, "1.20.1");

        File stateFile = tempDir.resolve("plugins.yml").toFile();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(stateFile);

        assertEquals(123, configuration.getInt("plugins.paper.build"));
        assertEquals("1.20.1", configuration.getString("plugins.paper.version"));

        Optional<UpdateStateRepository.PluginState> state = repository.find("paper");
        assertTrue(state.isPresent());
        assertEquals(123, state.get().build());
        assertEquals("1.20.1", state.get().version());
    }

    @Test
    void clearsVersionWhenNotProvided(@TempDir Path tempDir) {
        UpdateStateRepository repository = new UpdateStateRepository(tempDir.toFile(), logger);
        repository.saveLatestBuild("paper", 100, "1.0.0");

        repository.saveLatestBuild("paper", 101, null);

        File stateFile = tempDir.resolve("plugins.yml").toFile();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(stateFile);
        assertEquals(101, configuration.getInt("plugins.paper.build"));
        assertFalse(configuration.contains("plugins.paper.version"));
    }

    @Test
    void migratesLegacyStructure(@TempDir Path tempDir) throws IOException {
        Path stateFile = tempDir.resolve("plugins.yml");
        Files.writeString(stateFile, "paper: 10\nplugins:\n  geyser: 5\n");

        UpdateStateRepository repository = new UpdateStateRepository(tempDir.toFile(), logger);

        assertEquals(10, repository.getStoredBuild("paper"));
        assertEquals(5, repository.getStoredBuild("geyser"));

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(stateFile.toFile());
        assertEquals(10, configuration.getInt("plugins.paper.build"));
        assertEquals(5, configuration.getInt("plugins.geyser.build"));
        assertFalse(configuration.contains("paper"), "Legacy root node should be removed");
        assertEquals(-1, repository.getStoredBuild("unknown"));
    }
}
