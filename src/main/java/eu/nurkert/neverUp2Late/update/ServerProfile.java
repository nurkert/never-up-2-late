package eu.nurkert.neverUp2Late.update;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the running server offers a plugin that is about to be installed: the
 * Bukkit API version it implements and the plugins a dependency can resolve to.
 *
 * @param apiVersion  e.g. {@code 1.21.4}, or {@code null} when unknown
 * @param pluginNames lower case names and {@code provides} aliases of every
 *                    loaded plugin
 */
public record ServerProfile(String apiVersion, Set<String> pluginNames) {

    public static final ServerProfile UNKNOWN = new ServerProfile(null, Set.of());

    public ServerProfile {
        pluginNames = pluginNames == null ? Set.of() : Set.copyOf(pluginNames);
    }

    /**
     * Reads the profile of the running server. This happens on the update
     * thread, so anything Bukkit throws only means the checks that need it are
     * skipped - they exist to stop a broken install, not to cause a failed run.
     */
    public static ServerProfile current() {
        try {
            Server server = Bukkit.getServer();
            if (server == null) {
                return UNKNOWN;
            }
            Set<String> names = new TreeSet<>();
            for (Plugin plugin : server.getPluginManager().getPlugins()) {
                names.add(plugin.getName().toLowerCase(Locale.ROOT));
                for (String provided : plugin.getDescription().getProvides()) {
                    names.add(provided.toLowerCase(Locale.ROOT));
                }
            }
            return new ServerProfile(apiVersionOf(server.getBukkitVersion()), names);
        } catch (RuntimeException ex) {
            return UNKNOWN;
        }
    }

    /** {@code 1.21.4-R0.1-SNAPSHOT} becomes {@code 1.21.4}. */
    static String apiVersionOf(String bukkitVersion) {
        if (bukkitVersion == null || bukkitVersion.isBlank()) {
            return null;
        }
        int separator = bukkitVersion.indexOf('-');
        return (separator > 0 ? bukkitVersion.substring(0, separator) : bukkitVersion).trim();
    }

    public boolean provides(String pluginName) {
        return pluginName != null && pluginNames.contains(pluginName.toLowerCase(Locale.ROOT));
    }
}
