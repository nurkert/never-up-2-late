package eu.nurkert.neverUp2Late.handlers;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

public class InstallationHandler implements Listener {

        private boolean updateAvailable;

        // Step 1: Create a private static instance of the class
        private static InstallationHandler instance;

        // Step 2: Make the constructor private to prevent instantiation
        private InstallationHandler() {
            updateAvailable  = false;
        }

        // Step 3: Provide a public static method to get the instance of the class
        public static InstallationHandler getInstance() {
            if (instance == null) {
                instance = new InstallationHandler();
            }
            return instance;
        }

        public void updateAvailable() {
            if(Bukkit.getOnlinePlayers().size() > 0) {
                updateAvailable = true;
            } else {
                restartServer();
            }
        }

        @EventHandler
        public void onPlayerLeave(PlayerQuitEvent event) {
            if(updateAvailable) {
                if(Bukkit.getOnlinePlayers().size() - 1 == 0) {
                    restartServer();
                }
            }
        }

        private void restartServer() {
            Bukkit.shutdown();
        }
}
