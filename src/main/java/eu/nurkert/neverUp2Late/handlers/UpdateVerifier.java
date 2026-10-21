package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.persistence.PendingVerificationRepository;
import eu.nurkert.neverUp2Late.persistence.PendingVerificationRepository.Entry;
import eu.nurkert.neverUp2Late.update.UpdateCompletedEvent;
import eu.nurkert.neverUp2Late.update.UpdateCompletionListener;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.util.ArchiveUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Puts the previous version of a plugin back when its update does not come up.
 *
 * <p>Every check before an install looks at the jar; none of them can run it.
 * A plugin can pass all of them and still throw in {@code onEnable}, and then
 * the server starts without it - its commands gone, its data unprotected - and
 * the update that caused it is the last thing anyone suspects. So every plugin
 * update is remembered until the server has started with it once. If the plugin
 * is not enabled by then, the backup taken during the install goes back in its
 * place and the next restart brings the working version back.</p>
 *
 * <p>The version that failed is not installed again: the update state still
 * records it as current, so only a newer release is picked up.</p>
 */
public class UpdateVerifier implements UpdateCompletionListener {

    private final PendingVerificationRepository repository;
    private final String selfName;
    private final Logger logger;

    /**
     * @param selfName NeverUp2Late's own plugin name; a NeverUp2Late that does
     *                 not start cannot roll itself back, so it is not tracked
     */
    public UpdateVerifier(PendingVerificationRepository repository, String selfName, Logger logger) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.selfName = selfName;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** A plugin whose previous version was put back. */
    public record Rollback(Entry entry, Path restoredTo) {
    }

    @Override
    public void onUpdateCompleted(UpdateCompletedEvent event) {
        if (event.getSource().getTargetDirectory() != TargetDirectory.PLUGINS) {
            return;
        }
        Path backup = event.getReplacedFileBackup().orElse(null);
        if (backup == null) {
            // A first install has nothing to go back to.
            return;
        }
        Path artifact = event.getDownloadedArtifact().orElse(event.getDestination());
        String pluginName = ArchiveUtils.getPluginInfo(artifact)
                .map(ArchiveUtils.PluginInfo::name)
                .filter(name -> !name.isBlank())
                .orElse(null);
        if (pluginName == null || pluginName.equalsIgnoreCase(selfName)) {
            return;
        }
        repository.record(new Entry(pluginName, event.getSource().getName(), event.getDestination(), backup,
                event.getLatestVersion().orElse(null)));
    }

    /**
     * Settles every update that was waiting for a start. Meant to run once the
     * server has finished starting, when every plugin that is going to be
     * enabled is.
     *
     * @param isEnabled    whether a plugin of the given name is running
     * @param updateFolder where a restored jar goes when the failed one is still
     *                     locked, or {@code null}
     * @return the plugins whose previous version was put back
     */
    public List<Rollback> verifyAfterStartup(Predicate<String> isEnabled, Path updateFolder) {
        List<Rollback> rollbacks = new ArrayList<>();
        for (Entry entry : repository.entries()) {
            if (isEnabled.test(entry.pluginName())) {
                repository.remove(entry.pluginName());
                continue;
            }
            if (!Files.isRegularFile(entry.destination()) || !Files.isRegularFile(entry.backup())) {
                // Removed by hand since, or the backup was pruned: nothing left
                // that could be judged or restored.
                repository.remove(entry.pluginName());
                continue;
            }
            try {
                Path restoredTo = restore(entry, updateFolder);
                logger.log(Level.SEVERE,
                        "{0} {1} did not start, so the previous version was put back from {2}. It is active again after the next restart. The failed version will not be installed again; a newer release will be.",
                        new Object[]{entry.pluginName(), entry.version() != null ? entry.version() : "", entry.backup()});
                rollbacks.add(new Rollback(entry, restoredTo));
                repository.remove(entry.pluginName());
            } catch (IOException ex) {
                // Keep the entry: the next start tries again.
                logger.log(Level.SEVERE,
                        "{0} did not start after its update, and putting the previous version back failed ({1}). Restore it by hand from {2}.",
                        new Object[]{entry.pluginName(), ex.getMessage(), entry.backup()});
            }
        }
        return rollbacks;
    }

    private Path restore(Entry entry, Path updateFolder) throws IOException {
        try {
            Files.copy(entry.backup(), entry.destination(), StandardCopyOption.REPLACE_EXISTING);
            return entry.destination();
        } catch (IOException locked) {
            if (updateFolder == null || entry.destination().getFileName() == null) {
                throw locked;
            }
            Files.createDirectories(updateFolder);
            Path staged = updateFolder.resolve(entry.destination().getFileName());
            Files.copy(entry.backup(), staged, StandardCopyOption.REPLACE_EXISTING);
            return staged;
        }
    }
}
