package eu.nurkert.neverUp2Late.plugin;

/**
 * Exception raised when a plugin lifecycle operation fails. This wraps
 * reflective access issues as well as Bukkit loader errors so callers can
 * handle lifecycle problems uniformly.
 */
public class PluginLifecycleException extends Exception {

    public PluginLifecycleException(String message) {
        super(message);
    }

    public PluginLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
