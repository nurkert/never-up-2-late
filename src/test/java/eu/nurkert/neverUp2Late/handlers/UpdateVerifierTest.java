package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.persistence.PendingVerificationRepository;
import eu.nurkert.neverUp2Late.update.UpdateCompletedEvent;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateVerifierTest {

    @TempDir
    Path directory;

    private Path dataFolder;
    private Path plugins;
    private UpdateVerifier verifier;

    @BeforeEach
    void setUp() throws IOException {
        dataFolder = Files.createDirectories(directory.resolve("NeverUp2Late"));
        plugins = Files.createDirectories(directory.resolve("plugins"));
        verifier = newVerifier();
    }

    @Test
    void putsThePreviousVersionBackWhenTheUpdatedPluginDidNotStart() throws IOException {
        Path installed = jar(plugins.resolve("Demo.jar"), "Demo", "2.0");
        Path backup = jar(directory.resolve("Demo-1.0.jar"), "Demo", "1.0");
        verifier.onUpdateCompleted(event(installed, backup));

        // A new process: only the file on disk remembers the update.
        List<UpdateVerifier.Rollback> rollbacks = newVerifier().verifyAfterStartup(name -> false, null);

        assertEquals(1, rollbacks.size());
        assertEquals(installed, rollbacks.get(0).restoredTo());
        assertArrayEquals(Files.readAllBytes(backup), Files.readAllBytes(installed));
        assertTrue(newVerifier().verifyAfterStartup(name -> false, null).isEmpty(), "settled once, not again");
    }

    @Test
    void leavesAnUpdateThatStartedAlone() throws IOException {
        Path installed = jar(plugins.resolve("Demo.jar"), "Demo", "2.0");
        byte[] updated = Files.readAllBytes(installed);
        verifier.onUpdateCompleted(event(installed, jar(directory.resolve("Demo-1.0.jar"), "Demo", "1.0")));

        assertTrue(verifier.verifyAfterStartup("Demo"::equals, null).isEmpty());
        assertArrayEquals(updated, Files.readAllBytes(installed));
        assertTrue(verifier.verifyAfterStartup(name -> false, null).isEmpty(), "verified updates are forgotten");
    }

    @Test
    void doesNotTrackAFirstInstallOrItself() throws IOException {
        verifier.onUpdateCompleted(event(jar(plugins.resolve("Demo.jar"), "Demo", "2.0"), null));
        verifier.onUpdateCompleted(event(jar(plugins.resolve("NeverUp2Late.jar"), "NeverUp2Late", "3.0"),
                jar(directory.resolve("nu2l-old.jar"), "NeverUp2Late", "2.0")));

        assertTrue(new PendingVerificationRepository(dataFolder.toFile(), Logger.getLogger("test")).entries().isEmpty());
    }

    @Test
    void forgetsAPluginThatWasRemovedByHand() throws IOException {
        Path installed = jar(plugins.resolve("Demo.jar"), "Demo", "2.0");
        verifier.onUpdateCompleted(event(installed, jar(directory.resolve("Demo-1.0.jar"), "Demo", "1.0")));
        Files.delete(installed);

        assertTrue(verifier.verifyAfterStartup(name -> false, null).isEmpty());
        assertTrue(Files.notExists(installed), "a removed plugin must not be brought back");
    }

    private UpdateVerifier newVerifier() {
        return new UpdateVerifier(new PendingVerificationRepository(dataFolder.toFile(), Logger.getLogger("test")),
                "NeverUp2Late", Logger.getLogger("test"));
    }

    private UpdateCompletedEvent event(Path installed, Path backup) {
        UpdateSource source = new UpdateSource("demo", null, TargetDirectory.PLUGINS,
                installed.getFileName().toString(), null);
        return new UpdateCompletedEvent(source, installed, "2.0", 2, installed, null, backup);
    }

    private static Path jar(Path path, String pluginName, String version) throws IOException {
        try (OutputStream out = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(("name: " + pluginName + "\nversion: " + version + "\nmain: com.example.Main\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
