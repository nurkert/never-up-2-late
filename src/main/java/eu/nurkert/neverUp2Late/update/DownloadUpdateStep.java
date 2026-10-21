package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Downloads the latest artifact to the configured destination, using the
 * {@link ArtifactDownloader} for atomic writes and optional validation.
 */
public class DownloadUpdateStep implements UpdateStep {

    private final ArtifactDownloader artifactDownloader;
    private final Path updateFolder;

    public DownloadUpdateStep(ArtifactDownloader artifactDownloader) {
        this(artifactDownloader, null);
    }

    /**
     * @param updateFolder Bukkit's update folder (usually {@code plugins/update}),
     *                     or {@code null} to never stage updates there
     */
    public DownloadUpdateStep(ArtifactDownloader artifactDownloader, Path updateFolder) {
        this.artifactDownloader = artifactDownloader;
        this.updateFolder = updateFolder;
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
        // The staging file lives next to the target so the final move stays on
        // one filesystem and therefore atomic. It must NOT end in .jar: it sits
        // in the plugins directory, and a shutdown in the middle of a download
        // would otherwise leave a half-written jar that Bukkit happily tries to
        // load as a second copy of the plugin on the next start.
        Path staging = Files.createTempFile(parent, "nu2l-", "-" + safeFileName(targetPath) + ".part");

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

            // The last point at which both jars can be read. Everything that led
            // here worked from names in a config file; this compares the
            // plugin.yml of what was downloaded against the plugin.yml of what
            // is already on disk.
            Optional<String> conflict = InstallationGuard.findConflict(result, targetPath);
            if (conflict.isPresent()) {
                context.cancel("Installation refused: " + conflict.get());
                context.log(Level.WARNING,
                        "Refusing to install {0}: {1}. The downloaded file was discarded and nothing on disk changed.",
                        new Object[]{context.getSource().getName(), conflict.get()});
                return;
            }

            // The self-update has no second chance: every other plugin can be
            // repaired on the next cycle, but a NeverUp2Late that does not load
            // is a plugin folder nobody manages any more.
            Optional<String> identityProblem = InstallationGuard.findIdentityProblem(
                    result,
                    context.getSource().getInstalledPluginName(),
                    context.shouldVerifyDeclaredVersion() ? context.getLatestVersion() : null);
            if (identityProblem.isPresent()) {
                context.cancel("Installation refused: " + identityProblem.get());
                context.log(Level.SEVERE,
                        "Refusing to install {0}: {1}. The downloaded file was discarded and nothing on disk changed.",
                        new Object[]{context.getSource().getName(), identityProblem.get()});
                return;
            }

            Path backup = null;
            boolean hadPrevious = Files.exists(targetPath);
            try {
                backup = artifactDownloader.backupExistingFileCopy(
                        targetPath,
                        context.getSource().getInstalledPluginName(),
                        context.getSource().getName())
                        .map(ArtifactDownloader.BackupRecord::getPath)
                        .orElse(null);
            } catch (IOException ex) {
                // Overwriting anyway would destroy the only copy of a working
                // jar precisely when the place it should have been kept is
                // unavailable. The update can wait for the next cycle.
                context.cancel("Installation postponed: the previous file could not be backed up");
                context.log(Level.WARNING,
                        "Not installing {0}: the current file could not be backed up ({1}). Nothing on disk changed.",
                        new Object[]{context.getSource().getName(), ex.getMessage()});
                return;
            }

            try {
                moveReplacing(result, targetPath);
            } catch (IOException ex) {
                Optional<Path> staged = stageForNextStart(context, result, targetPath);
                if (staged.isPresent()) {
                    if (backup != null) {
                        context.setReplacedFileBackup(backup);
                    }
                    context.setDownloadedArtifact(staged.get());
                    context.setDownloadDestination(targetPath);
                    context.log(Level.INFO,
                            "{0} is in use and cannot be replaced while the server runs ({1}). The new version waits in {2} and the server swaps it in on the next start.",
                            new Object[]{context.getSource().getName(), ex.getMessage(), staged.get()});
                    return;
                }
                // The move is the only step that can fail with the old jar still
                // in place - typically a file lock on Windows. Leaving the fresh
                // backup behind would fill the backup folder with copies of the
                // version that is still installed and push the genuinely older
                // ones out, so the recovery path would hold nothing to recover.
                discardBackup(context, backup, hadPrevious);
                context.cancel("Installation postponed: " + describeMoveFailure(ex));
                context.log(Level.WARNING,
                        "Could not put the new {0} in place ({1}). The installed file is untouched; retrying next cycle.",
                        new Object[]{context.getSource().getName(), ex.getMessage()});
                return;
            }

            if (backup != null) {
                context.setReplacedFileBackup(backup);
            }
            context.setDownloadedArtifact(targetPath);
            context.setDownloadDestination(targetPath);
        } finally {
            deleteIfTemporary(staging, parent);
        }
    }

    /**
     * Removes a backup that was taken for an installation that then did not
     * happen.
     *
     * @param hadPrevious whether anything was actually replaced; when the
     *                    destination was empty the backup is not ours to judge
     */
    private void discardBackup(UpdateContext context, Path backup, boolean hadPrevious) {
        if (backup == null || !hadPrevious) {
            return;
        }
        try {
            Files.deleteIfExists(backup);
        } catch (IOException ex) {
            context.log(Level.FINE,
                    "Left a backup of the unchanged file behind at {0}: {1}",
                    new Object[]{backup, ex.getMessage()});
        }
    }

    private String describeMoveFailure(IOException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "the new file could not be moved into place";
        }
        return "the new file could not be moved into place (" + message + ")";
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
            throw new IOException("Downloaded file is not a valid jar/ZIP: " + expectedDestination, ex);
        }
    }

    /**
     * Hands a plugin jar that cannot be replaced in place - a running jar is
     * locked on Windows - to Bukkit's update folder. The server moves a file
     * from there over the jar of the same name before it loads plugins, which
     * is exactly the moment nothing holds the lock.
     *
     * @return where the file was staged, or empty when staging is not possible
     */
    private Optional<Path> stageForNextStart(UpdateContext context, Path downloaded, Path targetPath) {
        if (updateFolder == null
                || context.getSource().getTargetDirectory() != UpdateSourceRegistry.TargetDirectory.PLUGINS
                || targetPath.getFileName() == null) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(updateFolder);
            Path staged = updateFolder.resolve(targetPath.getFileName());
            moveReplacing(downloaded, staged);
            return Optional.of(staged);
        } catch (IOException stagingFailure) {
            context.log(Level.FINE, "Could not stage {0} in the update folder: {1}",
                    new Object[]{context.getSource().getName(), stagingFailure.getMessage()});
            return Optional.empty();
        }
    }

    // Package-private so a test can stand in for a file lock.
    void moveReplacing(Path source, Path target) throws IOException {
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
