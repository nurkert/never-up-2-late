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

    public UpdateContext(UpdateSource source, Path destination, Logger logger) {
        this.source = Objects.requireNonNull(source, "source");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.logger = Objects.requireNonNull(logger, "logger");
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

    public void log(Level level, String message, Object... args) {
        logger.log(level, message, args);
    }
}
