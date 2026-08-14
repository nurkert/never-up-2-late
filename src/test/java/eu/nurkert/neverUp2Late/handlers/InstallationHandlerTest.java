package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(players, shutdownCalls, logger);
        InstallationHandler handler = new InstallationHandler(server, createRepository(logger), logger, null, null, clock);

        handler.onUpdateCompleted(createEvent());

        assertEquals(1, shutdownCalls.get(), "Server should shut down when no players are online");
    }

    @Test
    void defersRestartUntilLastPlayerLeaves() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        players.add(createPlayer());
        Logger logger = Logger.getLogger("test");

        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(players, shutdownCalls, logger);
        InstallationHandler handler = new InstallationHandler(server, createRepository(logger), logger, null, null, clock);

        handler.onUpdateCompleted(createEvent());
        assertEquals(0, shutdownCalls.get(), "Restart must be deferred while players are online");

        // Bukkit fires PlayerQuitEvent while the quitting player is STILL listed
        // as online, so the list must stay populated here. Clearing it first (as
        // this test used to) hid the fact that the restart never fired.
        Player leaving = players.iterator().next();
        handler.onPlayerLeave(new PlayerQuitEvent(leaving, ""));
        assertEquals(1, shutdownCalls.get(), "Restart should happen once the last player leaves");
    }

    @Test
    void keepsWaitingWhileOtherPlayersRemainOnline() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        players.add(createPlayer());
        players.add(createPlayer());
        Logger logger = Logger.getLogger("test");

        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(players, shutdownCalls, logger);
        InstallationHandler handler = new InstallationHandler(server, createRepository(logger), logger, null, null, clock);

        handler.onUpdateCompleted(createEvent());

        Player leaving = players.iterator().next();
        handler.onPlayerLeave(new PlayerQuitEvent(leaving, ""));

        assertEquals(0, shutdownCalls.get(), "One player is still online, so the restart must keep waiting");
    }

    @Test
    void appliesEveryQueuedUpdateNotJustTheLastOne() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        players.add(createPlayer());
        Logger logger = Logger.getLogger("test");

        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(players, shutdownCalls, logger);
        StubLifecycleManager lifecycleManager = new StubLifecycleManager();
        lifecycleManager.reloadResult = true;
        InstallationHandler handler =
                new InstallationHandler(server, createRepository(logger), logger, lifecycleManager, null, clock);

        UpdateCompletedEvent first = createEvent("alpha", TargetDirectory.PLUGINS);
        UpdateCompletedEvent second = createEvent("beta", TargetDirectory.PLUGINS);
        lifecycleManager.known.add(first.getDestination());
        lifecycleManager.known.add(second.getDestination());

        handler.onUpdateCompleted(first);
        handler.onUpdateCompleted(second);
        assertEquals(0, lifecycleManager.reloadCount, "Nothing may be applied while a player is online");

        Player leaving = players.iterator().next();
        handler.onPlayerLeave(new PlayerQuitEvent(leaving, ""));

        assertEquals(2, lifecycleManager.reloadCount,
                "Both queued updates must be applied; a single pending slot dropped the first one");
        assertTrue(lifecycleManager.reloadedPaths.contains(first.getDestination()));
        assertTrue(lifecycleManager.reloadedPaths.contains(second.getDestination()));
    }

    @Test
    void neverLoadsAJarThatIsNotAKnownRunningPlugin() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        Logger logger = Logger.getLogger("test");

        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(players, shutdownCalls, logger);
        StubLifecycleManager lifecycleManager = new StubLifecycleManager();
        lifecycleManager.reloadResult = true;
        InstallationHandler handler =
                new InstallationHandler(server, createRepository(logger), logger, lifecycleManager, null, clock);

        // 'known' stays empty: the destination is not a plugin we track, which is
        // what happens right after the jar was renamed. Loading it by path would
        // start a SECOND copy of an already running plugin.
        handler.onUpdateCompleted(createEvent());

        assertEquals(0, lifecycleManager.reloadCount, "An unknown jar must not be loaded blind");
        assertEquals(1, shutdownCalls.get(), "It must fall through to the restart instead");
    }

    @Test
    void respectsRestartCooldownAcrossServerRestarts() throws IOException {
        Logger logger = Logger.getLogger("test");
        Path directory = Files.createTempDirectory("nu2l-restart-state-");
        directory.toFile().deleteOnExit();

        RestartCooldownRepository repository = new RestartCooldownRepository(directory.toFile(), logger);
        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));

        AtomicInteger firstShutdownCalls = new AtomicInteger();
        Server firstServer = createServer(new ArrayList<>(), firstShutdownCalls, logger);
        InstallationHandler firstHandler = new InstallationHandler(firstServer, repository, logger, null, null, clock);

        firstHandler.onUpdateCompleted(createEvent());
        assertEquals(1, firstShutdownCalls.get(), "Initial restart should be triggered");

        AtomicInteger secondShutdownCalls = new AtomicInteger();
        RestartCooldownRepository reloadedRepository = new RestartCooldownRepository(directory.toFile(), logger);
        Server secondServer = createServer(new ArrayList<>(), secondShutdownCalls, logger);
        InstallationHandler secondHandler = new InstallationHandler(secondServer, reloadedRepository, logger, null, null, clock);

        secondHandler.onUpdateCompleted(createEvent());
        assertEquals(0, secondShutdownCalls.get(), "Cooldown should prevent immediate restart loop");
    }

    @Test
    void skipsRestartWhenReloadSucceeds() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        Logger logger = Logger.getLogger("test");

        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(players, shutdownCalls, logger);
        RestartCooldownRepository repository = createRepository(logger);
        StubLifecycleManager lifecycleManager = new StubLifecycleManager();
        lifecycleManager.reloadResult = true;

        InstallationHandler handler = new InstallationHandler(server, repository, logger, lifecycleManager, null, clock);
        UpdateCompletedEvent event = createEvent();
        lifecycleManager.known.add(event.getDestination());

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

        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(players, shutdownCalls, logger);
        RestartCooldownRepository repository = createRepository(logger);
        StubLifecycleManager lifecycleManager = new StubLifecycleManager();
        lifecycleManager.reloadResult = false;

        InstallationHandler handler = new InstallationHandler(server, repository, logger, lifecycleManager, null, clock);
        UpdateCompletedEvent event = createEvent();
        lifecycleManager.known.add(event.getDestination());

        handler.onUpdateCompleted(event);

        assertTrue(lifecycleManager.reloadCalled, "Plugin reload should be attempted");
        assertEquals(1, shutdownCalls.get(), "Server restart should occur when reload fails");
    }

    @Test
    void defersPluginRestartOutsideMaintenanceWindow() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        Logger logger = Logger.getLogger("test");

        MutableClock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 1, 0));
        Server server = createServer(players, shutdownCalls, logger);
        InstallationHandler handler = new InstallationHandler(server, createRepository(logger), logger, null, null, clock);

        handler.onUpdateCompleted(createEvent());
        assertEquals(0, shutdownCalls.get(), "Restart should be deferred outside the maintenance window");

        clock.advance(Duration.ofHours(3));
        handler.tryExecutePendingEvent();

        assertEquals(1, shutdownCalls.get(), "Restart should occur once the maintenance window opens");
    }

    @Test
    void allowsImmediateRestartForGeyserAndPaper() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        Logger logger = Logger.getLogger("test");

        MutableClock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 1, 0));
        Server server = createServer(players, shutdownCalls, logger);

        InstallationHandler geyserHandler = new InstallationHandler(server, createRepository(logger), logger, null, null, clock);
        geyserHandler.onUpdateCompleted(createEvent("geyser", TargetDirectory.PLUGINS));

        InstallationHandler paperHandler = new InstallationHandler(server, createRepository(logger), logger, null, null, clock);
        paperHandler.onUpdateCompleted(createEvent("paper", TargetDirectory.SERVER));

        assertEquals(2, shutdownCalls.get(), "Geyser and Paper updates should restart immediately");
    }

    @Test
    void honoursRequireRestartEvenForAJarItDoesNotKnowByPath() throws IOException {
        // The gate used to sit behind the findByPath lookup, so a jar the manager
        // did not recognise was hot-reloaded although its setting said otherwise.
        AtomicInteger shutdownCalls = new AtomicInteger();
        Logger logger = Logger.getLogger("test");
        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(new ArrayList<>(), shutdownCalls, logger);

        StubLifecycleManager lifecycleManager = new StubLifecycleManager();
        lifecycleManager.reloadResult = true;
        PluginUpdateSettingsRepository settings = createSettingsRepository(logger);
        UpdateCompletedEvent event = createEvent();
        lifecycleManager.known.add(event.getDestination());
        // REQUIRE_RESTART is the default, so nothing has to be written here.

        InstallationHandler handler = new InstallationHandler(
                server, createRepository(logger), logger, lifecycleManager, settings, clock);
        handler.onUpdateCompleted(event);

        assertEquals(0, lifecycleManager.reloadCount, "REQUIRE_RESTART must not hot-reload");
        assertEquals(1, shutdownCalls.get(), "It must restart instead");
    }

    @Test
    void hotReloadsWhenTheSettingAsksForIt() throws IOException {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Logger logger = Logger.getLogger("test");
        Clock clock = MutableClock.fixedAt(LocalDateTime.of(2024, 1, 1, 4, 0));
        Server server = createServer(new ArrayList<>(), shutdownCalls, logger);

        StubLifecycleManager lifecycleManager = new StubLifecycleManager();
        lifecycleManager.reloadResult = true;
        PluginUpdateSettingsRepository settings = createSettingsRepository(logger);
        UpdateCompletedEvent event = createEvent();
        lifecycleManager.known.add(event.getDestination());
        settings.saveSettings(new StubManagedPlugin(event.getDestination()).getName(),
                new PluginUpdateSettingsRepository.PluginUpdateSettings(
                        true, PluginUpdateSettingsRepository.UpdateBehaviour.AUTO_RELOAD, false));

        InstallationHandler handler = new InstallationHandler(
                server, createRepository(logger), logger, lifecycleManager, settings, clock);
        handler.onUpdateCompleted(event);

        assertEquals(1, lifecycleManager.reloadCount, "AUTO_RELOAD must reload");
        assertEquals(0, shutdownCalls.get(), "A successful reload makes the restart unnecessary");
    }

    private PluginUpdateSettingsRepository createSettingsRepository(Logger logger) throws IOException {
        Path directory = Files.createTempDirectory("nu2l-settings-");
        directory.toFile().deleteOnExit();
        return new PluginUpdateSettingsRepository(directory.toFile(), logger);
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
        return createEvent("test", TargetDirectory.PLUGINS);
    }

    private UpdateCompletedEvent createEvent(String name, TargetDirectory targetDirectory) {
        UpdateSource source = new UpdateSource(name, null, targetDirectory, name + ".jar", null);
        Path destination = targetDirectory == TargetDirectory.SERVER
                ? Path.of(name + ".jar")
                : Path.of("plugins/" + name + ".jar");
        return new UpdateCompletedEvent(source, destination, "1.0", 1, destination, "");
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

    /** Stands in for a plugin the lifecycle manager already knows as running. */
    private record StubManagedPlugin(Path path) implements eu.nurkert.neverUp2Late.plugin.ManagedPlugin {
        @Override
        public String getName() {
            String name = path.getFileName().toString();
            return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        }

        @Override
        public Path getPath() {
            return path;
        }

        @Override
        public void attach(org.bukkit.plugin.Plugin plugin) {
        }

        @Override
        public java.util.Optional<org.bukkit.plugin.Plugin> getPlugin() {
            return java.util.Optional.empty();
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void load() {
        }

        @Override
        public void enable() {
        }

        @Override
        public void disable() {
        }

        @Override
        public void unload() {
        }

        @Override
        public void reload() {
        }
    }

    private static class StubLifecycleManager implements PluginLifecycleManager {
        boolean reloadCalled;
        boolean reloadResult;
        Path lastReloadPath;
        int reloadCount;
        final java.util.List<Path> reloadedPaths = new ArrayList<>();
        /** Paths the manager knows as currently running plugins. */
        final java.util.Set<Path> known = new java.util.LinkedHashSet<>();

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
            if (!known.contains(path)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new StubManagedPlugin(path));
        }

        @Override
        public boolean reloadPlugin(String name) {
            reloadCalled = true;
            return reloadResult;
        }

        @Override
        public boolean reloadPlugin(Path path) {
            reloadCalled = true;
            reloadCount++;
            lastReloadPath = path;
            reloadedPaths.add(path);
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

        @Override
        public void deleteAllDuplicates(String pluginName, Path preferredPath) {
        }

        @Override
        public java.util.Optional<eu.nurkert.neverUp2Late.plugin.ManagedPlugin> updateManagedPluginPath(Path oldPath, Path newPath) {
            return java.util.Optional.empty();
        }
    }

    private static class MutableClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zoneId;

        private MutableClock(Instant instant, ZoneId zoneId) {
            this.currentInstant = instant;
            this.zoneId = zoneId;
        }

        static MutableClock fixedAt(LocalDateTime dateTime) {
            ZoneId zone = ZoneId.systemDefault();
            return new MutableClock(dateTime.atZone(zone).toInstant(), zone);
        }

        void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
