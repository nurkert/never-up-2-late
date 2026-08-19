package eu.nurkert.neverUp2Late.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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
 * The check that stands in front of the one update with no second chance:
 * NeverUp2Late replacing its own jar.
 *
 * <p>Every other plugin can be repaired on the next cycle. This one cannot -
 * a NeverUp2Late that does not load is a plugin folder nobody manages any
 * more, and the operator finds out at the next restart.</p>
 */
class InstallationGuardIdentityTest {

    @TempDir
    Path dir;

    private static final String MAIN = "com.example.Main";

    @Test
    void acceptsTheArtifactTheReleasePromised() throws IOException {
        Path jar = jar("good.jar", "NeverUp2Late\nversion: 2.7.0\nmain: " + MAIN, MAIN, 61);

        assertFalse(InstallationGuard.findIdentityProblem(jar, "NeverUp2Late", "2.7.0").isPresent());
    }

    @Test
    void acceptsAVersionThatOnlyDiffersInSpelling() throws IOException {
        // The release is tagged v2.7.0 while plugin.yml says 2.7.0.
        Path jar = jar("good.jar", "NeverUp2Late\nversion: 2.7.0\nmain: " + MAIN, MAIN, 61);

        assertFalse(InstallationGuard.findIdentityProblem(jar, "NeverUp2Late", "v2.7.0").isPresent());
    }

    @Test
    void refusesAJarThatIsNotThePluginItShouldBe() throws IOException {
        Path jar = jar("wrong.jar", "SomethingElse\nversion: 2.7.0\nmain: " + MAIN, MAIN, 61);

        Optional<String> problem = InstallationGuard.findIdentityProblem(jar, "NeverUp2Late", "2.7.0");

        assertTrue(problem.isPresent());
        assertTrue(problem.get().contains("SomethingElse"), problem.get());
    }

    @Test
    void refusesAReleaseWhoseTagAndJarDisagree() throws IOException {
        // A mis-built release: tagged v2.7.0, jar still contains 2.6.0.
        Path jar = jar("stale.jar", "NeverUp2Late\nversion: 2.6.0\nmain: " + MAIN, MAIN, 61);

        Optional<String> problem = InstallationGuard.findIdentityProblem(jar, "NeverUp2Late", "2.7.0");

        assertTrue(problem.isPresent());
        assertTrue(problem.get().contains("2.6.0"), problem.get());
    }

    @Test
    void refusesAJarBuiltForANewerJavaThanThisServerRuns() throws IOException {
        int tooNew = Runtime.version().feature() + 44 + 1;
        Path jar = jar("future.jar", "NeverUp2Late\nversion: 2.7.0\nmain: " + MAIN, MAIN, tooNew);

        Optional<String> problem = InstallationGuard.findIdentityProblem(jar, "NeverUp2Late", "2.7.0");

        assertTrue(problem.isPresent(), "this is exactly the jar that loads nowhere and strands the server");
        assertTrue(problem.get().contains("Java"), problem.get());
    }

    @Test
    void refusesAJarWhoseEntryPointIsMissing() throws IOException {
        Path jar = jar("hollow.jar", "NeverUp2Late\nversion: 2.7.0\nmain: " + MAIN, null, 61);

        Optional<String> problem = InstallationGuard.findIdentityProblem(jar, "NeverUp2Late", "2.7.0");

        assertTrue(problem.isPresent());
        assertTrue(problem.get().contains(MAIN), problem.get());
    }

    @Test
    void refusesSomethingThatIsNotAPluginJarAtAll() throws IOException {
        // What the GitHub source-archive fallback used to hand over.
        Path zip = dir.resolve("source.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("never-up-2-late-2.7.0/README.md"));
            out.write("# source".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertTrue(InstallationGuard.findIdentityProblem(zip, "NeverUp2Late", "2.7.0").isPresent());
    }

    @Test
    void staysOutOfTheWayWhenNoPluginNameIsExpected() throws IOException {
        // Ordinary third-party sources pass null and keep the old behaviour.
        Path jar = jar("any.jar", "Whatever\nversion: 1.0.0\nmain: " + MAIN, MAIN, 61);

        assertFalse(InstallationGuard.findIdentityProblem(jar, null, null).isPresent());
    }

    /**
     * @param descriptor  the value of plugin.yml's {@code name:} plus any
     *                    further lines, written verbatim after "name: "
     * @param mainClass   class to include as the entry point, or null to omit it
     * @param classMajor  class file major version to stamp on it
     */
    private Path jar(String fileName, String descriptor, String mainClass, int classMajor) throws IOException {
        Path path = dir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(("name: " + descriptor + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            if (mainClass != null) {
                zip.putNextEntry(new ZipEntry(mainClass.replace('.', '/') + ".class"));
                zip.write(classFile(classMajor));
                zip.closeEntry();
            }
        }
        return path;
    }

    /** Just enough of a class file for the guard's header check to be real. */
    private byte[] classFile(int major) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(major);
        }
        return bytes.toByteArray();
    }
}
