package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Downloads the latest artifact to the configured destination, using the
 * {@link ArtifactDownloader} for atomic writes and optional validation.
 */
public class DownloadUpdateStep implements UpdateStep {

    private final ArtifactDownloader artifactDownloader;

    public DownloadUpdateStep(ArtifactDownloader artifactDownloader) {
        this.artifactDownloader = artifactDownloader;
    }

    @Override
    public void execute(UpdateContext context) throws Exception {
        if (context.isCancelled()) {
            return;
        }

        String downloadUrl = context.getDownloadUrl();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            context.cancel("Missing download URL");
            context.log(Level.WARNING, "No download URL configured for {0}", context.getSource().getName());
            return;
        }

        Path targetPath = context.getDownloadDestination();
        try {
            artifactDownloader.backupExistingFile(
                            targetPath,
                            context.getSource().getInstalledPluginName(),
                            context.getSource().getName())
                    .ifPresent(backup -> context.log(Level.INFO,
                            "Existing artifact moved to backup {0}", backup.getPath()));
        } catch (IOException ex) {
            context.cancel("Failed to backup existing file: " + ex.getMessage());
            context.log(Level.SEVERE,
                    "Unable to create backup for {0}: {1}",
                    new Object[]{context.getSource().getName(), ex.getMessage()});
            return;
        }

        ArtifactDownloader.DownloadRequest.Builder builder = ArtifactDownloader.DownloadRequest.builder()
                .url(downloadUrl)
                .destination(targetPath);

        context.getChecksumValidator().ifPresent(builder::checksumValidator);
        context.getDownloadHook().ifPresent(builder::hook);

        Path result = artifactDownloader.download(builder.build());

        if (context.getDownloadProcessor().isPresent()) {
            result = context.getDownloadProcessor().get().process(context, result);
        }

        context.setDownloadedArtifact(result);
        context.setDownloadDestination(result);
    }
}
