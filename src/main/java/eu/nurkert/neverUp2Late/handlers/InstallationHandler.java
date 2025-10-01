package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.update.UpdateCompletedEvent;
import eu.nurkert.neverUp2Late.update.UpdateCompletionListener;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

        public ServerRestartAction(Server server) {
            this.server = server;
        }

        @Override
        public void execute(UpdateCompletedEvent event) {
            server.shutdown();
        }

        @Override
        public String toString() {
            return "ServerRestartAction";
        }
    }
}
