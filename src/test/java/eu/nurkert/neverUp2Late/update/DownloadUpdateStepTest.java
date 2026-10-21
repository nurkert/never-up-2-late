package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.handlers.ArtifactDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void lockedPluginJarIsStagedInTheUpdateFolderInsteadOfRetriedForever(@TempDir Path tempDir) throws Exception {
        ArtifactDownloader downloader = new ArtifactDownloader(tempDir.resolve("backups"), 5);

        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        Path destination = pluginsDir.resolve("Demo.jar");
        byte[] oldBytes = writeZip(destination, "old.txt", "old");

        Path source = tempDir.resolve("source.jar");
        byte[] newBytes = writeZip(source, "new.txt", "new");

        var sourceMeta = new UpdateSourceRegistry.UpdateSource(
                "demo", null, UpdateSourceRegistry.TargetDirectory.PLUGINS, "Demo.jar", null);
        UpdateContext context = new UpdateContext(sourceMeta, destination, Logger.getLogger("test"));
        context.setDownloadUrl(source.toUri().toURL().toString());

        Path updateFolder = pluginsDir.resolve("update");
        DownloadUpdateStep step = new DownloadUpdateStep(downloader, updateFolder) {
            @Override
            void moveReplacing(Path from, Path target) throws IOException {
                if (target.equals(destination)) {
                    // What Windows reports for a jar the running server holds open.
                    throw new AccessDeniedException(target.toString());
                }
                super.moveReplacing(from, target);
            }
        };
        step.execute(context);

        assertFalse(context.isCancelled());
        assertArrayEquals(oldBytes, Files.readAllBytes(destination));
        Path staged = updateFolder.resolve("Demo.jar");
        assertArrayEquals(newBytes, Files.readAllBytes(staged));
        assertEquals(staged, context.getDownloadedArtifact().orElseThrow());
        assertEquals(destination, context.getDownloadDestination());
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

