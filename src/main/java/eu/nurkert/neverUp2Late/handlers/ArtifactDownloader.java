package eu.nurkert.neverUp2Late.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import eu.nurkert.neverUp2Late.net.HttpClient;

import java.net.URL;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Utility responsible for downloading update artifacts in an atomic fashion.
 * <p>
 * The downloader supports pluggable checksum validation and lifecycle hook
 * callbacks, enabling callers to react to the different download stages or add
 * custom validation logic without coupling it to the pipeline steps.
 */
public class ArtifactDownloader {

    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String DEFAULT_BACKUP_KEY = "default";

    private final Path backupsRoot;
    private final int maxBackups;

    public ArtifactDownloader() {
        this(null, 0);
    }

    public ArtifactDownloader(Path backupsDirectory, int maxBackups) {
        this.backupsRoot = backupsDirectory != null ? backupsDirectory.toAbsolutePath().normalize() : null;
        this.maxBackups = Math.max(0, maxBackups);
    }

    /**
     * Moves the provided {@code target} into the configured backup directory.
     *
     * @param target              file to backup
     * @param primaryIdentifier   preferred folder name (usually the installed plugin name)
     * @param secondaryIdentifier fallback folder name (for example the update source name)
     * @return an optional {@link BackupRecord} describing the created backup
     * @throws IOException if the backup directory cannot be created or the file cannot be moved
     */
    public Optional<BackupRecord> backupExistingFile(Path target,
                                                     String primaryIdentifier,
                                                     String secondaryIdentifier) throws IOException {
        if (!backupsEnabled() || target == null) {
            return Optional.empty();
        }

        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!Files.exists(normalizedTarget) || !Files.isRegularFile(normalizedTarget)) {
            return Optional.empty();
        }

        Path root = ensureBackupsRootExists();
        if (root == null) {
            return Optional.empty();
        }

        String key = resolveBackupKey(normalizedTarget, primaryIdentifier, secondaryIdentifier);
        Path pluginDirectory = root.resolve(key);
        Files.createDirectories(pluginDirectory);

        String fileName = normalizedTarget.getFileName() != null
                ? normalizedTarget.getFileName().toString()
                : "artifact.jar";
        String timestamp = BACKUP_TIMESTAMP_FORMATTER.format(LocalDateTime.now());
        Path backupPath = createUniqueBackupPath(pluginDirectory, timestamp, fileName);

        Files.move(normalizedTarget, backupPath, StandardCopyOption.REPLACE_EXISTING);
        pruneOldBackups(pluginDirectory);

        Instant createdAt = Instant.now();
        try {
            createdAt = Files.getLastModifiedTime(backupPath).toInstant();
        } catch (IOException ignored) {
        }

        return Optional.of(new BackupRecord(backupPath, createdAt));
    }

    /**
     * Restores the most recent backup for the supplied identifiers to the destination file.
     *
     * @param primaryIdentifier   preferred backup folder identifier
     * @param secondaryIdentifier fallback backup folder identifier
     * @param destination         file that should receive the backup contents
     * @return a {@link RestorationResult} describing the applied backup, if available
     * @throws IOException if the restoration fails or the backup directory cannot be accessed
     */
    public Optional<RestorationResult> restoreLatestBackup(String primaryIdentifier,
                                                           String secondaryIdentifier,
                                                           Path destination) throws IOException {
        if (!backupsEnabled() || destination == null) {
            return Optional.empty();
        }

        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path root = ensureBackupsRootExists();
        if (root == null) {
            return Optional.empty();
        }

        String key = resolveBackupKey(normalizedDestination, primaryIdentifier, secondaryIdentifier);
        Path pluginDirectory = root.resolve(key);
        if (!Files.isDirectory(pluginDirectory)) {
            return Optional.empty();
        }

        List<Path> backups = listBackups(pluginDirectory);
        if (backups.isEmpty()) {
            return Optional.empty();
        }

        Path backupToRestore = backups.get(0);
        Instant backupTimestamp = Instant.now();
        try {
            backupTimestamp = Files.getLastModifiedTime(backupToRestore).toInstant();
        } catch (IOException ignored) {
        }

        Path parent = normalizedDestination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.move(backupToRestore, normalizedDestination, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(new RestorationResult(normalizedDestination, backupToRestore, backupTimestamp));
    }

    /**
     * Restores the latest available backup while first preserving the current destination file.
     *
     * @param destination         file that should be replaced
     * @param primaryIdentifier   preferred backup folder identifier
     * @param secondaryIdentifier fallback backup folder identifier
     * @return details about the restored backup, if a previous backup exists
     * @throws IOException if file operations fail
     */
    public Optional<RestorationResult> restorePreviousBackup(Path destination,
                                                             String primaryIdentifier,
                                                             String secondaryIdentifier) throws IOException {
        if (!backupsEnabled() || destination == null) {
            return Optional.empty();
        }

        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path root = ensureBackupsRootExists();
        if (root == null) {
            return Optional.empty();
        }

        String key = resolveBackupKey(normalizedDestination, primaryIdentifier, secondaryIdentifier);
        Path pluginDirectory = root.resolve(key);
        if (!Files.isDirectory(pluginDirectory)) {
            return Optional.empty();
        }

        List<Path> backups = collectBackups(pluginDirectory);
        if (backups.isEmpty()) {
            return Optional.empty();
        }

        Path backupToRestore = backups.get(0);

        Optional<BackupRecord> currentBackup = backupExistingFile(normalizedDestination, primaryIdentifier, secondaryIdentifier);
        if (currentBackup.isPresent() && !Files.exists(backupToRestore)) {
            // Pruning removed the desired backup; restore the current file and abort.
            Files.move(currentBackup.get().getPath(), normalizedDestination, StandardCopyOption.REPLACE_EXISTING);
            return Optional.empty();
        }

        if (currentBackup.isPresent() && currentBackup.get().getPath().equals(backupToRestore)) {
            // The newest backup is the one we just created, there is nothing older to restore.
            Files.move(currentBackup.get().getPath(), normalizedDestination, StandardCopyOption.REPLACE_EXISTING);
            return Optional.empty();
        }

        Instant backupTimestamp = Instant.now();
        try {
            backupTimestamp = Files.getLastModifiedTime(backupToRestore).toInstant();
        } catch (IOException ignored) {
        }

        Path parent = normalizedDestination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.move(backupToRestore, normalizedDestination, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(new RestorationResult(normalizedDestination, backupToRestore, backupTimestamp));
    }

    private boolean backupsEnabled() {
        return backupsRoot != null;
    }

    private Path ensureBackupsRootExists() throws IOException {
        if (backupsRoot == null) {
            return null;
        }
        Files.createDirectories(backupsRoot);
        return backupsRoot;
    }

    private String resolveBackupKey(Path target, String... identifiers) {
        if (identifiers != null) {
            for (String identifier : identifiers) {
                String sanitised = sanitiseKey(identifier);
                if (sanitised != null) {
                    return sanitised;
                }
            }
        }
        if (target != null) {
            Path fileName = target.getFileName();
            if (fileName != null) {
                String sanitised = sanitiseKey(fileName.toString());
                if (sanitised != null) {
                    return sanitised;
                }
            }
        }
        return DEFAULT_BACKUP_KEY;
    }

    private String sanitiseKey(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String sanitised = trimmed.replaceAll("[^a-zA-Z0-9._-]+", "-");
        sanitised = sanitised.replaceAll("-{2,}", "-");
        sanitised = sanitised.replaceAll("^[._-]+", "");
        sanitised = sanitised.replaceAll("[._-]+$", "");
        return sanitised.isEmpty() ? null : sanitised;
    }

    private Path createUniqueBackupPath(Path pluginDirectory, String timestamp, String fileName) throws IOException {
        Path candidate = pluginDirectory.resolve(timestamp + "-" + fileName);
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = pluginDirectory.resolve(timestamp + "-" + counter++ + "-" + fileName);
        }
        return candidate;
    }

    private List<Path> collectBackups(Path pluginDirectory) throws IOException {
        try (var stream = Files.list(pluginDirectory)) {
            return stream
                    .filter(path -> Files.isRegularFile(path))
                    .sorted((a, b) -> b.getFileName().toString().compareToIgnoreCase(a.getFileName().toString()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private List<Path> listBackups(Path pluginDirectory) throws IOException {
        if (!Files.isDirectory(pluginDirectory)) {
            return List.of();
        }
        return collectBackups(pluginDirectory);
    }

    private void pruneOldBackups(Path pluginDirectory) throws IOException {
        if (maxBackups <= 0) {
            return;
        }
        List<Path> backups = collectBackups(pluginDirectory);
        for (int i = maxBackups; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    /**
     * Executes a download based on the supplied {@link DownloadRequest}.
     *
     * @param request fully described download request
     * @return the final destination path of the downloaded artifact
     * @throws IOException in case of networking or file system issues
     */
    public Path download(DownloadRequest request) throws IOException {
        Objects.requireNonNull(request, "request");

        URLConnection connection = openConnection(request);
        Path destination = request.getDestination();
        Path tempFile = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".download");

        DownloadHook hook = Optional.ofNullable(request.getHook()).orElse(DownloadHook.NO_OP);
        hook.onStart(request.getUrl(), destination);

        try {
            copyToTempFile(connection, tempFile, request.getChecksumValidator());
            moveAtomically(tempFile, destination);
            hook.onSuccess(destination);
            return destination;
        } catch (IOException | RuntimeException ex) {
            hook.onFailure(destination, ex);
            throw ex;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private URLConnection openConnection(DownloadRequest request) throws IOException {
        URL url = new URL(request.getUrl());
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(request.getConnectTimeout());
        connection.setReadTimeout(request.getReadTimeout());
        if (connection.getRequestProperty("User-Agent") == null) {
            connection.setRequestProperty("User-Agent", HttpClient.DEFAULT_USER_AGENT);
        }

        String host = url.getHost();
        if (host != null) {
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.contains("curseforge.com") || normalizedHost.contains("forgecdn.net")) {
                if (connection.getRequestProperty("Accept") == null) {
                    connection.setRequestProperty("Accept", "*/*");
                }
                connection.setRequestProperty("Referer", "https://www.curseforge.com/");
            }
        }
        return connection;
    }

    private void copyToTempFile(URLConnection connection,
                                Path tempFile,
                                ChecksumValidator checksumValidator) throws IOException {
        try (InputStream inputStream = connection.getInputStream();
             OutputStream outputStream = Files.newOutputStream(tempFile, StandardOpenOption.WRITE)) {

            if (checksumValidator == null || !checksumValidator.requiresDigest()) {
                inputStream.transferTo(outputStream);
                if (checksumValidator != null) {
                    checksumValidator.validate(tempFile, null);
                }
                return;
            }

            MessageDigest digest = checksumValidator.createDigest();
            try (DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                digestInputStream.transferTo(outputStream);
            }
            checksumValidator.validate(tempFile, digest);
        }
    }

    private void moveAtomically(Path tempFile, Path destination) throws IOException {
        try {
            Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Builder backed request describing a single download action.
     */
    public static class DownloadRequest {
        private final String url;
        private final Path destination;
        private final int connectTimeout;
        private final int readTimeout;
        private final ChecksumValidator checksumValidator;
        private final DownloadHook hook;

        private DownloadRequest(Builder builder) {
            this.url = builder.url;
            this.destination = builder.destination;
            this.connectTimeout = builder.connectTimeout;
            this.readTimeout = builder.readTimeout;
            this.checksumValidator = builder.checksumValidator;
            this.hook = builder.hook;
        }

        public String getUrl() {
            return url;
        }

        public Path getDestination() {
            return destination;
        }

        public int getConnectTimeout() {
            return connectTimeout;
        }

        public int getReadTimeout() {
            return readTimeout;
        }

        public ChecksumValidator getChecksumValidator() {
            return checksumValidator;
        }

        public DownloadHook getHook() {
            return hook;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String url;
            private Path destination;
            private int connectTimeout = 5_000;
            private int readTimeout = 10_000;
            private ChecksumValidator checksumValidator;
            private DownloadHook hook;

            public Builder url(String url) {
                this.url = url;
                return this;
            }

            public Builder destination(Path destination) {
                this.destination = destination;
                return this;
            }

            public Builder connectTimeout(int connectTimeout) {
                this.connectTimeout = connectTimeout;
                return this;
            }

            public Builder readTimeout(int readTimeout) {
                this.readTimeout = readTimeout;
                return this;
            }

            public Builder checksumValidator(ChecksumValidator checksumValidator) {
                this.checksumValidator = checksumValidator;
                return this;
            }

            public Builder hook(DownloadHook hook) {
                this.hook = hook;
                return this;
            }

            public DownloadRequest build() {
                Objects.requireNonNull(url, "url");
                Objects.requireNonNull(destination, "destination");
                return new DownloadRequest(this);
            }
        }
    }

    /**
     * Strategy interface for checksum validation. Implementations can decide if
     * a digest should be calculated and how the resulting checksum is validated.
     */
    public interface ChecksumValidator {

        /**
         * @return {@code true} if a {@link MessageDigest} should be created and
         * supplied to {@link #validate(Path, MessageDigest)}, otherwise
         * {@code false}.
         */
        default boolean requiresDigest() {
            return false;
        }

        /**
         * Creates a {@link MessageDigest} instance if {@link #requiresDigest()}
         * returns {@code true}.
         */
        default MessageDigest createDigest() throws IOException {
            throw new IOException("Checksum validator does not provide a MessageDigest");
        }

        /**
         * Validates the downloaded file.
         *
         * @param file   temporary file containing the downloaded artifact
         * @param digest calculated digest or {@code null} if not requested
         * @throws IOException if validation fails
         */
        void validate(Path file, MessageDigest digest) throws IOException;

        /**
         * Convenience factory to create a validator that calculates a digest and
         * compares it to the expected hex encoded checksum value.
         */
        static ChecksumValidator fromHexChecksum(String algorithm, String expectedChecksum) {
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(expectedChecksum, "expectedChecksum");

            return new ChecksumValidator() {
                @Override
                public boolean requiresDigest() {
                    return true;
                }

                @Override
                public MessageDigest createDigest() throws IOException {
                    try {
                        return MessageDigest.getInstance(algorithm);
                    } catch (NoSuchAlgorithmException ex) {
                        throw new IOException("Unsupported checksum algorithm: " + algorithm, ex);
                    }
                }

                @Override
                public void validate(Path file, MessageDigest digest) throws IOException {
                    if (digest == null) {
                        throw new IOException("Checksum validation requested but no digest available");
                    }
                    String actual = HexFormat.of().formatHex(digest.digest());
                    if (!expectedChecksum.equalsIgnoreCase(actual)) {
                        throw new IOException("Checksum mismatch for " + file + ": expected "
                                + expectedChecksum + " but was " + actual);
                    }
                }
            };
        }
    }

    /**
     * Hook interface to observe the different lifecycle stages of a download.
     */
    public interface DownloadHook {
        DownloadHook NO_OP = new DownloadHook() {
        };

        default void onStart(String url, Path destination) {
        }

        default void onSuccess(Path destination) {
        }

        default void onFailure(Path destination, Exception exception) {
        }
    }

    /**
     * Describes a backup that has been created for an existing artifact.
     */
    public static final class BackupRecord {
        private final Path path;
        private final Instant createdAt;

        public BackupRecord(Path path, Instant createdAt) {
            this.path = Objects.requireNonNull(path, "path");
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }

        public Path getPath() {
            return path;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }
    }

    /**
     * Provides details about a backup that has been restored to a destination.
     */
    public static final class RestorationResult {
        private final Path destination;
        private final Path originalBackupPath;
        private final Instant backupTimestamp;

        public RestorationResult(Path destination, Path originalBackupPath, Instant backupTimestamp) {
            this.destination = Objects.requireNonNull(destination, "destination");
            this.originalBackupPath = Objects.requireNonNull(originalBackupPath, "originalBackupPath");
            this.backupTimestamp = Objects.requireNonNull(backupTimestamp, "backupTimestamp");
        }

        public Path getDestination() {
            return destination;
        }

        public Path getOriginalBackupPath() {
            return originalBackupPath;
        }

        public Instant getBackupTimestamp() {
            return backupTimestamp;
        }
    }
}
