package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.persistence.RestartCooldownRepository;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.update.UpdateCompletedEvent;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallationHandlerTest {

    @Test
    void restartsImmediatelyWhenNoPlayersOnline() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        Logger logger = Logger.getLogger("test");

        Server server = createServer(players, shutdownCalls, logger);
        InstallationHandler handler = new InstallationHandler(server, createRepository(logger), logger);

        handler.onUpdateCompleted(createEvent());

        assertEquals(1, shutdownCalls.get(), "Server should shut down when no players are online");
    }

    @Test
    void defersRestartUntilLastPlayerLeaves() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        players.add(createPlayer());
        Logger logger = Logger.getLogger("test");

        Server server = createServer(players, shutdownCalls, logger);
        InstallationHandler handler = new InstallationHandler(server, createRepository(logger), logger);

        handler.onUpdateCompleted(createEvent());
        assertEquals(0, shutdownCalls.get(), "Restart must be deferred while players are online");

        handler.onPlayerLeave(new PlayerQuitEvent(createPlayer(), ""));
        assertEquals(1, shutdownCalls.get(), "Restart should happen once the last player leaves");
    }

    @Test
    void respectsRestartCooldownAcrossServerRestarts() throws IOException {
        Logger logger = Logger.getLogger("test");
        Path directory = Files.createTempDirectory("nu2l-restart-state-");
        directory.toFile().deleteOnExit();

        RestartCooldownRepository repository = new RestartCooldownRepository(directory.toFile(), logger);

        AtomicInteger firstShutdownCalls = new AtomicInteger();
        Server firstServer = createServer(new ArrayList<>(), firstShutdownCalls, logger);
        InstallationHandler firstHandler = new InstallationHandler(firstServer, repository, logger);

        firstHandler.onUpdateCompleted(createEvent());
        assertEquals(1, firstShutdownCalls.get(), "Initial restart should be triggered");

        AtomicInteger secondShutdownCalls = new AtomicInteger();
        RestartCooldownRepository reloadedRepository = new RestartCooldownRepository(directory.toFile(), logger);
        Server secondServer = createServer(new ArrayList<>(), secondShutdownCalls, logger);
        InstallationHandler secondHandler = new InstallationHandler(secondServer, reloadedRepository, logger);

        secondHandler.onUpdateCompleted(createEvent());
        assertEquals(0, secondShutdownCalls.get(), "Cooldown should prevent immediate restart loop");
    }

    @Test
    void skipsRestartWhenReloadSucceeds() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        Logger logger = Logger.getLogger("test");

        Server server = createServer(players, shutdownCalls, logger);
        RestartCooldownRepository repository = createRepository(logger);
        StubLifecycleManager lifecycleManager = new StubLifecycleManager();
        lifecycleManager.reloadResult = true;

        InstallationHandler handler = new InstallationHandler(server, repository, logger, lifecycleManager, null, true);
        UpdateCompletedEvent event = createEvent();

        handler.onUpdateCompleted(event);

        assertTrue(lifecycleManager.reloadCalled, "Plugin reload should be attempted");
        assertEquals(0, shutdownCalls.get(), "Server restart should be skipped when reload succeeds");
        assertEquals(event.getDestination(), lifecycleManager.lastReloadPath);
    }

    @Test
    void restartsWhenReloadFails() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        Logger logger = Logger.getLogger("test");

        Server server = createServer(players, shutdownCalls, logger);
        RestartCooldownRepository repository = createRepository(logger);
        StubLifecycleManager lifecycleManager = new StubLifecycleManager();
        lifecycleManager.reloadResult = false;

        InstallationHandler handler = new InstallationHandler(server, repository, logger, lifecycleManager, null, true);

        handler.onUpdateCompleted(createEvent());

        assertTrue(lifecycleManager.reloadCalled, "Plugin reload should be attempted");
        assertEquals(1, shutdownCalls.get(), "Server restart should occur when reload fails");
    }

    private RestartCooldownRepository createRepository(Logger logger) throws IOException {
        Path directory = Files.createTempDirectory("nu2l-restart-state-");
        directory.toFile().deleteOnExit();
        return new RestartCooldownRepository(directory.toFile(), logger);
    }

    private Server createServer(Collection<Player> players, AtomicInteger shutdownCalls, Logger logger) {
        return (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getOnlinePlayers":
                            return players;
                        case "shutdown":
                            shutdownCalls.incrementAndGet();
                            return null;
                        case "getLogger":
                            return logger;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                }
        );
    }

    private UpdateCompletedEvent createEvent() {
        UpdateSource source = new UpdateSource("test", null, TargetDirectory.PLUGINS, "test.jar", null);
        return new UpdateCompletedEvent(source, Path.of("plugins/test.jar"), "1.0", 1, Path.of("plugins/test.jar"), "");
    }

    private Player createPlayer() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (void.class.equals(returnType)) {
            return null;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        throw new IllegalStateException("Unsupported primitive type: " + returnType);
    }

    private static class StubLifecycleManager implements PluginLifecycleManager {
        boolean reloadCalled;
        boolean reloadResult;
        Path lastReloadPath;

        @Override
        public void registerPlugin(org.bukkit.plugin.Plugin plugin) {
        }

        @Override
        public void registerLoadedPlugins(org.bukkit.plugin.Plugin self) {
        }

        @Override
        public Collection<eu.nurkert.neverUp2Late.plugin.ManagedPlugin> getManagedPlugins() {
            return Collections.emptyList();
        }

        @Override
        public java.util.Optional<eu.nurkert.neverUp2Late.plugin.ManagedPlugin> findByName(String name) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<eu.nurkert.neverUp2Late.plugin.ManagedPlugin> findByPath(Path path) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean reloadPlugin(String name) {
            reloadCalled = true;
            return reloadResult;
        }

        @Override
        public boolean reloadPlugin(Path path) {
            reloadCalled = true;
            lastReloadPath = path;
            return reloadResult;
        }

        @Override
        public boolean enablePlugin(String name) {
            return false;
        }

        @Override
        public boolean disablePlugin(String name) {
            return false;
        }

        @Override
        public boolean unloadPlugin(String name) {
            return false;
        }

        @Override
        public boolean loadPlugin(Path path) {
            return false;
        }
    }
}
