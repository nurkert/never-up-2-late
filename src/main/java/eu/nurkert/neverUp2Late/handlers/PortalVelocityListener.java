package eu.nurkert.neverUp2Late.handlers;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Restores the player's velocity after travelling through a portal so the
 * momentum gained before entering the portal is preserved on the other side.
 *
 * <p>Paper/Bukkit resets the player's velocity when they are teleported which
 * makes momentum based portal transport (for example dropping into a floor
 * portal) feel sluggish. By capturing the velocity right before the teleport
 * and reapplying it on the next tick we can reproduce the expected "speedy
 * thing goes in, speedy thing comes out" behaviour while also clearing the
 * fall distance to avoid accidental fall damage when entering a horizontal
 * portal.</p>
 */
public final class PortalVelocityListener implements Listener {

    private final JavaPlugin plugin;

    public PortalVelocityListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        Vector originalVelocity = player.getVelocity().clone();

        // Schedule velocity restoration for the next tick so the teleport has
        // finished and the player is positioned at the destination portal.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.setVelocity(originalVelocity);
            player.setFallDistance(0.0f);
        });
    }
}
