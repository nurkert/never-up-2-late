package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import eu.nurkert.neverUp2Late.persistence.RestartCooldownRepository;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The install step must not announce a finished update straight away: the
 * caller still renames the jar to its upstream filename afterwards, and a
 * reload triggered too early would race against that move.
 */
class InstallUpdateStepTest {

    @TempDir
    Path directory;

    private final Logger logger = Logger.getLogger("nu2l-install-step-test");

    private RecordingInstallationHandler installationHandler;
    private PersistentPluginHandler persistentPluginHandler;
    private InstallUpdateStep step;

    @BeforeEach
    void setUp() {
        installationHandler = new RecordingInstallationHandler(
                emptyServer(), new RestartCooldownRepository(directory.toFile(), logger), logger);
        persistentPluginHandler = new PersistentPluginHandler(
                new UpdateStateRepository(directory.toFile(), logger));
        step = new InstallUpdateStep(null, persistentPluginHandler, installationHandler);
    }

    @Test
    void doesNotAnnounceTheUpdateBeforeTheCallerDispatchesIt() {
        UpdateContext context = createContext();

        step.execute(context);

        assertNull(installationHandler.received, "the event must wait until the file is settled");
        assertEquals(7, persistentPluginHandler.getStoredBuild("test"),
                "the new build is recorded immediately either way");
    }

    @Test
    void announcesTheDestinationTheFileEndedUpAt() {
        UpdateContext context = createContext();
        step.execute(context);

        // What the caller does after the pipeline: rename to the upstream name.
        Path renamed = directory.resolve("test-1.2.3.jar");
        context.setDownloadDestination(renamed);
        context.setDownloadedArtifact(renamed);
        context.dispatchCompletion();

        assertNotNull(installationHandler.received);
        assertEquals(renamed, installationHandler.received.getDestination());
        assertEquals(renamed, installationHandler.received.getDownloadedArtifact().orElse(null));
    }

    @Test
    void dispatchesAtMostOnce() {
        UpdateContext context = createContext();
        step.execute(context);

        context.dispatchCompletion();
        assertNotNull(installationHandler.received);

        installationHandler.received = null;
        context.dispatchCompletion();
        assertNull(installationHandler.received, "a second dispatch must not fire the event again");
    }

    @Test
    void cancelledUpdatesAnnounceNothing() {
        UpdateContext context = createContext();
        context.cancel("No new build available");

        step.execute(context);
        context.dispatchCompletion();

        assertNull(installationHandler.received);
    }

    private UpdateContext createContext() {
        UpdateSource source = new UpdateSource("test", null, TargetDirectory.PLUGINS, "test.jar", null);
        UpdateContext context = new UpdateContext(source, directory.resolve("test.jar"), logger);
        context.setLatestBuild(7);
        context.setLatestVersion("1.2.3");
        context.setDownloadUrl("https://example.invalid/test.jar");
        context.setDownloadedArtifact(directory.resolve("test.jar"));
        return context;
    }

    private static Server emptyServer() {
        return (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> null);
    }

    private static final class RecordingInstallationHandler extends InstallationHandler {

        private UpdateCompletedEvent received;

        private RecordingInstallationHandler(Server server,
                                             RestartCooldownRepository restartCooldownRepository,
                                             Logger logger) {
            super(server, restartCooldownRepository, logger);
        }

        @Override
        public void onUpdateCompleted(UpdateCompletedEvent event) {
            received = event;
        }
    }
}
