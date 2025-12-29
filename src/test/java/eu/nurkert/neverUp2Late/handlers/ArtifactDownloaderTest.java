package eu.nurkert.neverUp2Late.handlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactDownloaderTest {

    @Test
    void downloadBacksUpExistingFileWithoutRemovingDestination(@TempDir Path tempDir) throws Exception {
        Path backupsDir = tempDir.resolve("backups");
        ArtifactDownloader downloader = new ArtifactDownloader(backupsDir, 10);

        Path destinationDir = tempDir.resolve("server");
        Files.createDirectories(destinationDir);
        Path destination = destinationDir.resolve("paper.jar");
        byte[] oldBytes = writeZip(destination, "old.txt", "old");

        Path source = tempDir.resolve("source.jar");
        byte[] newBytes = writeZip(source, "new.txt", "new");
        String url = source.toUri().toURL().toString();

        ArtifactDownloader.DownloadRequest request = ArtifactDownloader.DownloadRequest.builder()
                .url(url)
                .destination(destination)
                .backupExisting(true)
                .backupIdentifiers("paper", "paper")
                .build();

        downloader.download(request);

        assertTrue(Files.exists(destination));
        assertArrayEquals(newBytes, Files.readAllBytes(destination));

        Path backupFolder = backupsDir.resolve("paper");
        assertTrue(Files.isDirectory(backupFolder));

        List<Path> backups;
        try (Stream<Path> stream = Files.list(backupFolder)) {
            backups = stream.filter(Files::isRegularFile).toList();
        }

        assertFalse(backups.isEmpty());
        assertArrayEquals(oldBytes, Files.readAllBytes(backups.get(0)));
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
