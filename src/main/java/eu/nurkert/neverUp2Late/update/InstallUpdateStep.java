package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.InstallationHandler;
import eu.nurkert.neverUp2Late.handlers.PersistentPluginHandler;

/**
 * Persists the newly installed build and triggers post installation actions via
 * the {@link InstallationHandler}.
 */
public class InstallUpdateStep implements UpdateStep {

    private final PersistentPluginHandler persistentPluginHandler;
    private final InstallationHandler installationHandler;

    public InstallUpdateStep(PersistentPluginHandler persistentPluginHandler,
                             InstallationHandler installationHandler) {
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
                context.getDestination(),
                context.getLatestVersion(),
                context.getLatestBuild(),
                context.getDownloadedArtifact().orElse(null),
                context.getDownloadUrl());
        installationHandler.onUpdateCompleted(event);
    }
}
