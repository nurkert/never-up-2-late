package eu.nurkert.neverUp2Late.handlers;

import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class InstallationHandler implements Listener {

        private final Server server;
        private boolean updateAvailable;

        public InstallationHandler(Server server) {
            this.server = server;
            this.updateAvailable  = false;
        }

        public void updateAvailable() {
            if (!server.getOnlinePlayers().isEmpty()) {
                updateAvailable = true;
            } else {
                restartServer();
            }
        }

        @EventHandler
        public void onPlayerLeave(PlayerQuitEvent event) {
            if (updateAvailable && server.getOnlinePlayers().size() <= 1) {
                restartServer();
            }
        }

        private void restartServer() {
            server.shutdown();
        }
}
