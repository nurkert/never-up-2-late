package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Level;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

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
        Path parent = targetPath.getParent();
        if (parent == null) {
            context.cancel("Invalid download destination");
            context.log(Level.WARNING, "Download destination has no parent directory for {0}: {1}",
                    new Object[]{context.getSource().getName(), targetPath});
            return;
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, "nu2l-", "-" + safeFileName(targetPath));

        try {
            ArtifactDownloader.DownloadHook hook = context.getDownloadHook().orElse(null);
            ArtifactDownloader.DownloadHook mappedHook = hook == null ? null : new ArtifactDownloader.DownloadHook() {
                @Override
                public void onStart(String url, Path destination) {
                    hook.onStart(url, targetPath);
                }

                @Override
                public void onSuccess(Path destination) {
                    hook.onSuccess(targetPath);
                }

                @Override
                public void onFailure(Path destination, Exception exception) {
                    hook.onFailure(targetPath, exception);
                }
            };

            ArtifactDownloader.DownloadRequest.Builder builder = ArtifactDownloader.DownloadRequest.builder()
                    .url(downloadUrl)
                    .destination(staging);

            context.getChecksumValidator().ifPresent(builder::checksumValidator);
            if (mappedHook != null) {
                builder.hook(mappedHook);
            }

            Path result = artifactDownloader.download(builder.build());

            if (context.getDownloadProcessor().isPresent()) {
                result = context.getDownloadProcessor().get().process(context, result);
            }

            validateArchiveIfExpected(targetPath, result);

            try {
                artifactDownloader.backupExistingFileCopy(
                        targetPath,
                        context.getSource().getInstalledPluginName(),
                        context.getSource().getName());
            } catch (IOException ex) {
                context.log(Level.WARNING,
                        "Download prepared but backup of previous artifact failed: {0}",
                        ex.getMessage());
            }

            moveReplacing(result, targetPath);
            context.setDownloadedArtifact(targetPath);
            context.setDownloadDestination(targetPath);
        } catch (Exception ex) {
            throw ex;
        } finally {
            deleteIfTemporary(staging, parent);
        }
    }

    private String safeFileName(Path targetPath) {
        if (targetPath == null || targetPath.getFileName() == null) {
            return "artifact.jar";
        }
        String name = targetPath.getFileName().toString().trim();
        return name.isEmpty() ? "artifact.jar" : name;
    }

    private void validateArchiveIfExpected(Path expectedDestination, Path fileToValidate) throws IOException {
        if (expectedDestination == null || fileToValidate == null) {
            return;
        }
        Path fileName = expectedDestination.getFileName();
        if (fileName == null) {
            return;
        }
        String lower = fileName.toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) {
            return;
        }
        try (ZipFile zipFile = new ZipFile(fileToValidate.toFile())) {
            if (zipFile.size() == 0) {
                throw new IOException("Downloaded archive is empty: " + expectedDestination);
            }
        } catch (ZipException ex) {
            throw new IOException("Downloaded file is not a valid JAR/ZIP: " + expectedDestination, ex);
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteIfTemporary(Path path, Path expectedParent) {
        if (path == null || expectedParent == null) {
            return;
        }
        try {
            Path normalizedPath = path.toAbsolutePath().normalize();
            Path normalizedParent = expectedParent.toAbsolutePath().normalize();
            if (!normalizedParent.equals(normalizedPath.getParent())) {
                return;
            }
            Path fileName = normalizedPath.getFileName();
            if (fileName == null || !fileName.toString().startsWith("nu2l-")) {
                return;
            }
            Files.deleteIfExists(normalizedPath);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
