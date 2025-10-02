package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Persists the newly installed build and triggers post installation actions via
 * the {@link InstallationHandler}.
 */
public class InstallUpdateStep implements UpdateStep {

    private final JavaPlugin plugin;
    private final PersistentPluginHandler persistentPluginHandler;
    private final InstallationHandler installationHandler;

    public InstallUpdateStep(JavaPlugin plugin,
                             PersistentPluginHandler persistentPluginHandler,
                             InstallationHandler installationHandler) {
        this.plugin = plugin;
        this.persistentPluginHandler = persistentPluginHandler;
        this.installationHandler = installationHandler;
    }

    @Override
    public void execute(UpdateContext context) {
        if (context.isCancelled()) {
            return;
        }

        String key = context.getSource().getName();
        persistentPluginHandler.saveLatestBuild(
                key,
                context.getLatestBuild(),
                context.getLatestVersion());

        UpdateCompletedEvent event = new UpdateCompletedEvent(
                context.getSource(),
                context.getDownloadDestination(),
                context.getLatestVersion(),
                context.getLatestBuild(),
                context.getDownloadedArtifact().orElse(null),
                context.getDownloadUrl());
        if (plugin != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> installationHandler.onUpdateCompleted(event));
        } else {
            installationHandler.onUpdateCompleted(event);
        }
    }
}
