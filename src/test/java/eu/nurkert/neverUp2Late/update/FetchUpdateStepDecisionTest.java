package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant behind the "some of my plugins even downgraded" reports: an
 * update runs when, and only when, the source offers something newer.
 *
 * <p>Three separate branches used to reach "download" without comparing
 * versions at all - a destination that could not be found under its configured
 * name, a build number that happened to be larger, and a version string that
 * merely differed. Each test below feeds the step a remote artifact that is
 * plainly older and expects it to be left alone.</p>
 */
class FetchUpdateStepDecisionTest {

    @TempDir
    Path plugins;

    private final Logger logger = Logger.getLogger("nu2l-decision-test");

    private PersistentPluginHandler state;
    private FetchUpdateStep step;

    @BeforeEach
    void setUp() {
        state = new PersistentPluginHandler(new UpdateStateRepository(plugins.toFile(), logger));
        step = new FetchUpdateStep(state, new VersionComparator(), true);
    }

    @Test
    void installsAGenuinelyNewerVersion() throws Exception {
        installed("MyPlugin.jar");
        state.saveLatestBuild("myplugin", 5, "1.0.0");

        assertTrue(runsUpdate(new StubFetcher("2.0.0", 6, "1.0.0")));
    }

    @Test
    void leavesANewerInstalledVersionAloneWhenTheBuildCounterDisagrees() throws Exception {
        installed("MyPlugin.jar");
        state.saveLatestBuild("myplugin", 100, "2.0.0");

        assertFalse(runsUpdate(new StubFetcher("1.0.0", 200, "2.0.0")),
                "a larger build number must not outrank a newer installed version");
    }

    @Test
    void treatsADifferentVersionStringAsOlderRatherThanNewer() throws Exception {
        installed("MyPlugin.jar");
        state.saveLatestBuild("myplugin", UpdateFetcher.UNKNOWN_BUILD, "3.0.0");

        assertFalse(runsUpdate(new StubFetcher("1.0.0", UpdateFetcher.UNKNOWN_BUILD, null)),
                "no installed version reported, so the recorded 3.0.0 decides - and 1.0.0 is not newer");
    }

    @Test
    void doesNotReinstallWhenOnlyTheConfiguredFileNameIsStale() throws Exception {
        // config.yml says MyPlugin.jar; the plugin is there under its real name.
        pluginJar("MyPlugin-9.9.9.jar", "MyPlugin");
        state.saveLatestBuild("myplugin", 999, "9.9.9");

        assertFalse(runsUpdate(new StubFetcher("0.0.1", 1, "9.9.9")),
                "a missing destination means a stale filename, not a licence to install an older build");
    }

    @Test
    void putsBackAJarThatWasActuallyDeleted() throws Exception {
        // Recorded, but gone from the folder entirely - not renamed. Restoring
        // it can neither downgrade anything nor create a second copy.
        state.saveLatestBuild("myplugin", 999, "9.9.9");

        assertTrue(runsUpdate(new StubFetcher("9.9.9", 999, null)));
    }

    @Test
    void installsTheFirstTimeASourceIsAdded() throws Exception {
        // Nothing recorded, nothing on disk: the operator just added this source.
        assertTrue(runsUpdate(new StubFetcher("1.0.0", 1, null)));
    }

    @Test
    void doesNotReapplyAVersionThatWasRolledBackByHand() throws Exception {
        installed("MyPlugin.jar");
        state.saveLatestBuild("myplugin", 7, "2.0.0");

        assertFalse(runsUpdate(new StubFetcher("2.0.0", 7, "1.0.0")),
                "2.0.0 was already written once and the operator put 1.0.0 back on purpose");
    }

    private void installed(String fileName) throws IOException {
        pluginJar(fileName, "MyPlugin");
    }

    /** A jar the guard can actually read a plugin name out of. */
    private void pluginJar(String fileName, String pluginName) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(plugins.resolve(fileName)))) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(("name: " + pluginName + "\nversion: 1.0.0\nmain: com.example.Main\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /** @return whether the step decided to download. */
    private boolean runsUpdate(UpdateFetcher fetcher) throws Exception {
        UpdateSource source = new UpdateSource(
                "myplugin", fetcher, TargetDirectory.PLUGINS, "MyPlugin.jar", "MyPlugin");
        UpdateContext context = new UpdateContext(source, plugins.resolve("MyPlugin.jar"), logger);
        step.execute(context);
        return !context.isCancelled();
    }

    private static final class StubFetcher implements UpdateFetcher {

        private final String latestVersion;
        private final int latestBuild;
        private final String installedVersion;

        private StubFetcher(String latestVersion, int latestBuild, String installedVersion) {
            this.latestVersion = latestVersion;
            this.latestBuild = latestBuild;
            this.installedVersion = installedVersion;
        }

        @Override
        public void loadLatestBuildInfo() {
        }

        @Override
        public String getLatestVersion() {
            return latestVersion;
        }

        @Override
        public int getLatestBuild() {
            return latestBuild;
        }

        @Override
        public String getLatestDownloadUrl() {
            return "https://example.invalid/MyPlugin.jar";
        }

        @Override
        public String getInstalledVersion() {
            return installedVersion;
        }
    }
}
