package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadUpdateStepTest {

    @Test
    void downloadProcessorFailureDoesNotOverwriteExistingArtifact(@TempDir Path tempDir) throws Exception {
        Path backupsDir = tempDir.resolve("backups");
        ArtifactDownloader downloader = new ArtifactDownloader(backupsDir, 5);

        Path serverDir = tempDir.resolve("server");
        Files.createDirectories(serverDir);
        Path destination = serverDir.resolve("paper.jar");
        byte[] oldBytes = writeZip(destination, "old.txt", "old");

        Path source = tempDir.resolve("source.jar");
        writeZip(source, "new.txt", "new");
        String url = source.toUri().toURL().toString();

        var sourceMeta = new eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource(
                "paper",
                null,
                eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory.SERVER,
                "paper.jar",
                "paper");
        UpdateContext context = new UpdateContext(sourceMeta, destination, Logger.getLogger("test"));
        context.setDownloadUrl(url);
        context.setDownloadProcessor((ctx, downloadedFile) -> {
            throw new IOException("processor failed");
        });

        DownloadUpdateStep step = new DownloadUpdateStep(downloader);
        assertThrows(IOException.class, () -> step.execute(context));

        assertArrayEquals(oldBytes, Files.readAllBytes(destination));

        try (Stream<Path> stream = Files.list(serverDir)) {
            List<Path> leftovers = stream
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().startsWith("nu2l-"))
                    .toList();
            assertTrue(leftovers.isEmpty());
        }
    }

    private static byte[] writeZip(Path path, String entryName, String contents) throws Exception {
        try (OutputStream outputStream = Files.newOutputStream(path);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            ZipEntry entry = new ZipEntry(entryName);
            zipOutputStream.putNextEntry(entry);
            zipOutputStream.write(contents.getBytes());
            zipOutputStream.closeEntry();
        }
        return Files.readAllBytes(path);
    }
}

