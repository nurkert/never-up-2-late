package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.update.UpdateCompletedEvent;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstallationHandlerTest {

    @Test
    void restartsImmediatelyWhenNoPlayersOnline() {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();

        Server server = createServer(players, shutdownCalls);
        InstallationHandler handler = new InstallationHandler(server);

        handler.onUpdateCompleted(createEvent());

        assertEquals(1, shutdownCalls.get(), "Server should shut down when no players are online");
    }

    @Test
    void defersRestartUntilLastPlayerLeaves() {
        AtomicInteger shutdownCalls = new AtomicInteger();
        Collection<Player> players = new ArrayList<>();
        players.add(createPlayer());

        Server server = createServer(players, shutdownCalls);
        InstallationHandler handler = new InstallationHandler(server);

        handler.onUpdateCompleted(createEvent());
        assertEquals(0, shutdownCalls.get(), "Restart must be deferred while players are online");

        handler.onPlayerLeave(new PlayerQuitEvent(createPlayer(), ""));
        assertEquals(1, shutdownCalls.get(), "Restart should happen once the last player leaves");
    }

    private Server createServer(Collection<Player> players, AtomicInteger shutdownCalls) {
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
                            return java.util.logging.Logger.getLogger("test");
                        default:
                            return defaultValue(method.getReturnType());
                    }
                }
        );
    }

    private UpdateCompletedEvent createEvent() {
        UpdateSource source = new UpdateSource("test", null, TargetDirectory.PLUGINS, "test.jar");
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
}
