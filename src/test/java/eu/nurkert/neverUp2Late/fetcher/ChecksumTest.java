package eu.nurkert.neverUp2Late.fetcher;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class ChecksumTest {

    @Test
    void acceptsHexOfTheLengthTheAlgorithmProduces() {
        assertEquals(new JsonUpdateFetcher.Checksum("SHA-256", "ab".repeat(32)),
                JsonUpdateFetcher.Checksum.of("SHA-256", " " + "AB".repeat(32) + " ").orElseThrow());
    }

    @Test
    void dropsValuesThatCannotBeAChecksumInsteadOfFailingTheUpdate() {
        assertTrue(JsonUpdateFetcher.Checksum.of("SHA-256", "ab".repeat(16)).isEmpty());
        assertTrue(JsonUpdateFetcher.Checksum.of("SHA-256", "zz".repeat(32)).isEmpty());
        assertTrue(JsonUpdateFetcher.Checksum.of("MD5", "ab".repeat(16)).isEmpty());
        assertTrue(JsonUpdateFetcher.Checksum.of("SHA-256", null).isEmpty());
    }

    @Test
    void validatorRejectsATamperedDownload(@TempDir Path directory) throws Exception {
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        ArtifactDownloader.ChecksumValidator validator =
                ArtifactDownloader.ChecksumValidator.fromHexChecksum("SHA-256", expected);

        MessageDigest matching = validator.createDigest();
        matching.update(original);
        assertDoesNotThrow(() -> validator.validate(directory.resolve("plugin.jar"), matching));

        MessageDigest tampered = validator.createDigest();
        tampered.update("tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> validator.validate(directory.resolve("plugin.jar"), tampered));
    }
}
