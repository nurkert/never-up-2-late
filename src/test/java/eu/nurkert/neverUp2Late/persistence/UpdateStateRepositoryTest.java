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
    void remembersWhatTheLastCheckFound(@TempDir Path tempDir) {
        UpdateStateRepository repository = new UpdateStateRepository(tempDir.toFile(), logger);

        repository.saveCheckState("paper", new UpdateStateRepository.CheckState(
                "1.20.4", 244, 1_700_000_000_000L, UpdateStateRepository.CheckResult.UPDATED, null));

        Optional<UpdateStateRepository.CheckState> state = repository.findCheckState("paper");
        assertTrue(state.isPresent());
        assertEquals("1.20.4", state.get().latestVersion());
        assertEquals(244, state.get().latestBuild());
        assertEquals(1_700_000_000_000L, state.get().checkedAt());
        assertEquals(UpdateStateRepository.CheckResult.UPDATED, state.get().result());
    }

    @Test
    void keepsTheCheckStateAcrossAReload(@TempDir Path tempDir) {
        // The schema validation used to delete every field that was not build or
        // version, so anything recorded about a check vanished on the next start.
        UpdateStateRepository first = new UpdateStateRepository(tempDir.toFile(), logger);
        first.saveLatestBuild("geyser", 10, "2.4.2");
        first.saveCheckState("geyser", new UpdateStateRepository.CheckState(
                "2.4.3", 11, 1_700_000_000_000L, UpdateStateRepository.CheckResult.FAILED, "HTTP 403"));

        UpdateStateRepository reloaded = new UpdateStateRepository(tempDir.toFile(), logger);

        Optional<UpdateStateRepository.CheckState> state = reloaded.findCheckState("geyser");
        assertTrue(state.isPresent(), "the recorded check must survive a restart");
        assertEquals("2.4.3", state.get().latestVersion());
        assertEquals(UpdateStateRepository.CheckResult.FAILED, state.get().result());
        assertEquals("HTTP 403", state.get().error());
        assertEquals(10, reloaded.getStoredBuild("geyser"), "and must not disturb the installed state");
    }

    @Test
    void reportsNothingForASourceThatWasNeverChecked(@TempDir Path tempDir) {
        UpdateStateRepository repository = new UpdateStateRepository(tempDir.toFile(), logger);
        repository.saveLatestBuild("paper", 1, "1.20.1");

        assertTrue(repository.findCheckState("paper").isEmpty());
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
