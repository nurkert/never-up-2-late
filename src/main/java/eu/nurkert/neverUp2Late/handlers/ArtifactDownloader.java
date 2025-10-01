package eu.nurkert.neverUp2Late.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Utility responsible for downloading update artifacts in an atomic fashion.
 * <p>
 * The downloader supports pluggable checksum validation and lifecycle hook
 * callbacks, enabling callers to react to the different download stages or add
 * custom validation logic without coupling it to the pipeline steps.
 */
public class ArtifactDownloader {

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
        URLConnection connection = new URL(request.getUrl()).openConnection();
        connection.setConnectTimeout(request.getConnectTimeout());
        connection.setReadTimeout(request.getReadTimeout());
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
}
