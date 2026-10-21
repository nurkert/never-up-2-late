package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Event emitted by the update pipeline after a successful installation. The
 * event is intentionally simple so different subsystems can react to it via
 * callback interfaces or Bukkit listeners.
 */
public class UpdateCompletedEvent {

    private final UpdateSource source;
    private final Path destination;
    private final String latestVersion;
    private final int latestBuild;
    private final Path downloadedArtifact;
    private final String downloadUrl;
    private final Path replacedFileBackup;

    public UpdateCompletedEvent(UpdateSource source,
                                Path destination,
                                String latestVersion,
                                int latestBuild,
                                Path downloadedArtifact,
                                String downloadUrl) {
        this(source, destination, latestVersion, latestBuild, downloadedArtifact, downloadUrl, null);
    }

    /**
     * @param replacedFileBackup the copy kept of the file the update replaced,
     *                           or {@code null} if nothing was replaced
     */
    public UpdateCompletedEvent(UpdateSource source,
                                Path destination,
                                String latestVersion,
                                int latestBuild,
                                Path downloadedArtifact,
                                String downloadUrl,
                                Path replacedFileBackup) {
        this.source = Objects.requireNonNull(source, "source");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.latestVersion = latestVersion;
        this.latestBuild = latestBuild;
        this.downloadedArtifact = downloadedArtifact;
        this.downloadUrl = downloadUrl;
        this.replacedFileBackup = replacedFileBackup;
    }

    public UpdateSource getSource() {
        return source;
    }

    public Path getDestination() {
        return destination;
    }

    public Optional<String> getLatestVersion() {
        return Optional.ofNullable(latestVersion);
    }

    public int getLatestBuild() {
        return latestBuild;
    }

    public Optional<Path> getDownloadedArtifact() {
        return Optional.ofNullable(downloadedArtifact);
    }

    public Optional<String> getDownloadUrl() {
        return Optional.ofNullable(downloadUrl);
    }

    public Optional<Path> getReplacedFileBackup() {
        return Optional.ofNullable(replacedFileBackup);
    }
}
