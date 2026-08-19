package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared context passed between {@link UpdateStep UpdateSteps}. The context
 * exposes immutable information about the current {@link UpdateSource} as well
 * as mutable state collected while executing the pipeline.
 */
public class UpdateContext {

    private final UpdateSource source;
    private final Path destination;
    private final Logger logger;

    private boolean cancelled;
    private String cancelReason;
    private String downloadUrl;
    private int latestBuild;
    private String latestVersion;
    private Path downloadedArtifact;
    private ArtifactDownloader.ChecksumValidator checksumValidator;
    private ArtifactDownloader.DownloadHook downloadHook;
    private DownloadProcessor downloadProcessor;
    private boolean retainUpstreamFilename;
    private String remoteFilename;
    private Path downloadDestination;
    private Path replacedFileBackup;
    private boolean verifyDeclaredVersion;
    private Runnable completionDispatcher;

    public UpdateContext(UpdateSource source, Path destination, Logger logger) {
        this.source = Objects.requireNonNull(source, "source");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.downloadDestination = destination;
    }

    public UpdateSource getSource() {
        return source;
    }

    public UpdateFetcher getFetcher() {
        return source.getFetcher();
    }

    public Path getDestination() {
        return destination;
    }

    public Logger getLogger() {
        return logger;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel(String reason) {
        this.cancelled = true;
        this.cancelReason = reason;
    }

    public Optional<String> getCancelReason() {
        return Optional.ofNullable(cancelReason);
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public int getLatestBuild() {
        return latestBuild;
    }

    public void setLatestBuild(int latestBuild) {
        this.latestBuild = latestBuild;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    public Optional<Path> getDownloadedArtifact() {
        return Optional.ofNullable(downloadedArtifact);
    }

    public void setDownloadedArtifact(Path downloadedArtifact) {
        this.downloadedArtifact = downloadedArtifact;
    }

    public Optional<ArtifactDownloader.ChecksumValidator> getChecksumValidator() {
        return Optional.ofNullable(checksumValidator);
    }

    public void setChecksumValidator(ArtifactDownloader.ChecksumValidator checksumValidator) {
        this.checksumValidator = checksumValidator;
    }

    public Optional<ArtifactDownloader.DownloadHook> getDownloadHook() {
        return Optional.ofNullable(downloadHook);
    }

    public void setDownloadHook(ArtifactDownloader.DownloadHook downloadHook) {
        this.downloadHook = downloadHook;
    }

    public Optional<DownloadProcessor> getDownloadProcessor() {
        return Optional.ofNullable(downloadProcessor);
    }

    public void setDownloadProcessor(DownloadProcessor downloadProcessor) {
        this.downloadProcessor = downloadProcessor;
    }

    public boolean shouldRetainUpstreamFilename() {
        return retainUpstreamFilename;
    }

    public void setRetainUpstreamFilename(boolean retainUpstreamFilename) {
        this.retainUpstreamFilename = retainUpstreamFilename;
    }

    public Optional<String> getRemoteFilename() {
        return Optional.ofNullable(remoteFilename);
    }

    public void setRemoteFilename(String remoteFilename) {
        this.remoteFilename = remoteFilename;
    }

    /**
     * Where the file that was replaced by this update has been kept.
     *
     * <p>A backup nobody is told about is not a recovery path. The updater
     * reports this alongside the finished installation so an operator who ends
     * up with a plugin they did not want knows where the previous jar is.</p>
     */
    public Optional<Path> getReplacedFileBackup() {
        return Optional.ofNullable(replacedFileBackup);
    }

    public void setReplacedFileBackup(Path replacedFileBackup) {
        this.replacedFileBackup = replacedFileBackup;
    }

    /**
     * Whether the jar has to declare the exact version the source announced.
     *
     * <p>Set for NeverUp2Late updating itself. A release whose tag and jar
     * disagree is a mis-built release, and for every other plugin that is a
     * nuisance the next cycle can correct - for this one it is a server that
     * comes back up without the thing that manages its plugins.</p>
     */
    public boolean shouldVerifyDeclaredVersion() {
        return verifyDeclaredVersion;
    }

    public void setVerifyDeclaredVersion(boolean verifyDeclaredVersion) {
        this.verifyDeclaredVersion = verifyDeclaredVersion;
    }

    public Path getDownloadDestination() {
        return downloadDestination != null ? downloadDestination : destination;
    }

    public void setDownloadDestination(Path downloadDestination) {
        this.downloadDestination = downloadDestination;
    }

    /**
     * Registers the notification that announces the finished installation. The
     * pipeline does not send it itself: the caller still has to settle the file
     * on disk (renaming it to the upstream filename, cleaning up duplicates)
     * and only then is the destination final. Dispatching earlier would let a
     * plugin reload race against a jar that is about to move.
     */
    public void setCompletionDispatcher(Runnable completionDispatcher) {
        this.completionDispatcher = completionDispatcher;
    }

    /**
     * Sends a pending completion notification, if any. Calling it more than
     * once is harmless; only the first call dispatches.
     */
    public void dispatchCompletion() {
        Runnable dispatcher = completionDispatcher;
        completionDispatcher = null;
        if (dispatcher != null) {
            dispatcher.run();
        }
    }

    public void log(Level level, String message, Object... args) {
        logger.log(level, message, args);
    }
}
