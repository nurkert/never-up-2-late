package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.update.UpdateCompletedEvent;
import eu.nurkert.neverUp2Late.update.UpdateCompletionListener;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class InstallationHandler implements Listener, UpdateCompletionListener {

    private final Server server;
    private final List<PostUpdateAction> actions = new CopyOnWriteArrayList<>();
    private volatile UpdateCompletedEvent pendingEvent;

    public InstallationHandler(Server server) {
        this.server = server;
        registerAction(new ServerRestartAction(server));
    }

    public void registerAction(PostUpdateAction action) {
        actions.add(action);
    }

    @Override
    public void onUpdateCompleted(UpdateCompletedEvent event) {
        if (!server.getOnlinePlayers().isEmpty()) {
            pendingEvent = event;
            return;
        }
        executeActions(event);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        UpdateCompletedEvent pending = pendingEvent;
        if (pending != null && server.getOnlinePlayers().size() <= 1) {
            pendingEvent = null;
            executeActions(pending);
        }
    }

    private void executeActions(UpdateCompletedEvent event) {
        for (PostUpdateAction action : actions) {
            try {
                action.execute(event);
            } catch (Exception ex) {
                server.getLogger().log(Level.SEVERE, "Failed to execute post update action " + action, ex);
            }
        }
    }

    public interface PostUpdateAction {
        void execute(UpdateCompletedEvent event) throws Exception;
    }

    public static class ServerRestartAction implements PostUpdateAction {
        private final Server server;
        private static final long RESTART_COOLDOWN_MILLIS = Duration.ofHours(1).toMillis();
        private static final AtomicLong LAST_RESTART_TIME = new AtomicLong(0L);

        public ServerRestartAction(Server server) {
            this.server = server;
        }

        @Override
        public void execute(UpdateCompletedEvent event) {
            long now = System.currentTimeMillis();

            while (true) {
                long lastRestart = LAST_RESTART_TIME.get();
                long elapsed = now - lastRestart;

                if (lastRestart != 0L && elapsed < RESTART_COOLDOWN_MILLIS) {
                    long remainingMillis = RESTART_COOLDOWN_MILLIS - elapsed;
                    Duration remaining = Duration.ofMillis(remainingMillis);
                    long minutes = remaining.toMinutes();
                    long seconds = remaining.minusMinutes(minutes).getSeconds();

                    server.getLogger().log(
                            Level.INFO,
                            String.format(
                                    "Skipping server restart; cooldown active for %d minute(s) and %d second(s).",
                                    minutes,
                                    seconds
                            )
                    );
                    return;
                }

                if (LAST_RESTART_TIME.compareAndSet(lastRestart, now)) {
                    break;
                }
            }

            server.shutdown();
        }

        @Override
        public String toString() {
            return "ServerRestartAction";
        }
    }
}
