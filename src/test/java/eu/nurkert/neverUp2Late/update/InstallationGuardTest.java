package eu.nurkert.neverUp2Late.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two invariants that stand between an update and the review that started
 * this: never write onto a jar that belongs to somebody else, and never leave
 * two copies of the same plugin in the folder.
 */
class InstallationGuardTest {

    @TempDir
    Path plugins;

    @Test
    void refusesToOverwriteAJarThatBelongsToAnotherPlugin() throws IOException {
        Path incoming = jar("staging.part", "Skript", "2.9.0");
        Path occupied = jar("MyPlugin.jar", "EssentialsX", "2.20.1");

        Optional<String> conflict = InstallationGuard.findConflict(incoming, occupied);

        assertTrue(conflict.isPresent(), "EssentialsX' jar must not be replaced by Skript");
        assertTrue(conflict.get().contains("EssentialsX"), conflict.get());
    }

    @Test
    void refusesToCreateASecondJarOfAPluginThatIsAlreadyInstalled() throws IOException {
        Path incoming = jar("staging.part", "EssentialsX", "2.20.1");
        jar("EssentialsX-2.19.0.jar", "EssentialsX", "2.19.0");
        Path destination = plugins.resolve("essentials.jar");

        Optional<String> conflict = InstallationGuard.findConflict(incoming, destination);

        assertTrue(conflict.isPresent(),
                "the plugin is already there under its real name; a second jar makes Bukkit drop one of them");
        assertTrue(conflict.get().contains("EssentialsX-2.19.0.jar"), conflict.get());
    }

    @Test
    void allowsAnUpdateThatReplacesTheSamePluginInPlace() throws IOException {
        Path incoming = jar("staging.part", "EssentialsX", "2.20.1");
        Path destination = jar("EssentialsX.jar", "EssentialsX", "2.19.0");

        assertFalse(InstallationGuard.findConflict(incoming, destination).isPresent());
    }

    @Test
    void allowsAFirstInstallIntoAnEmptyFolder() throws IOException {
        Path incoming = jar("staging.part", "EssentialsX", "2.20.1");

        assertFalse(InstallationGuard.findConflict(incoming, plugins.resolve("EssentialsX.jar")).isPresent());
    }

    @Test
    void staysOutOfTheWayForArtifactsThatAreNotPlugins() throws IOException {
        // A server jar has no plugin.yml, so there is no identity to compare and
        // nothing for the guard to have an opinion about.
        Path incoming = plugins.resolve("staging.part");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(incoming))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertFalse(InstallationGuard.findConflict(incoming, plugins.resolve("paper.jar")).isPresent());
    }

    private Path jar(String fileName, String pluginName, String version) throws IOException {
        Path path = plugins.resolve(fileName);
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
