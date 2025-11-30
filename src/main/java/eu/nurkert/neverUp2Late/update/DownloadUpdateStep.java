package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;

import java.io.IOException;
import java.nio.file.Files;
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
        ArtifactDownloader.BackupRecord backupRecord = null;
        // Backup jetzt NACH erfolgreichem Download (transaktionales Replace in ArtifactDownloader)

        try {
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
            // Nach erfolgreichem Download altes File sichern (falls überhaupt vorhanden)
            try {
                backupRecord = artifactDownloader.backupExistingFile(
                                targetPath,
                                context.getSource().getInstalledPluginName(),
                                context.getSource().getName())
                        .orElse(null);
                if (backupRecord != null) {
                    context.log(Level.FINE, "Previous artifact archived at {0}", backupRecord.getPath());
                }
            } catch (IOException ex) {
                context.log(Level.WARNING,
                        "Download succeeded but backup of previous artifact failed: {0}",
                        ex.getMessage());
            }
        } catch (Exception ex) {
            throw ex;
        }
    }
}
