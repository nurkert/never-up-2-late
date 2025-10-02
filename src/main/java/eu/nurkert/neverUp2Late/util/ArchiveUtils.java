package eu.nurkert.neverUp2Late.util;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.file.StandardCopyOption;

/**
 * Utility helpers for inspecting archive files such as ZIPs.
 */
public final class ArchiveUtils {

    private ArchiveUtils() {
    }

    public static List<ArchiveEntry> listJarEntries(Path archive) throws IOException {
        if (archive == null || !Files.exists(archive)) {
            return List.of();
        }
        try (FileSystem fs = FileSystems.newFileSystem(archive)) {
            List<ArchiveEntry> result = new ArrayList<>();
            for (Path root : fs.getRootDirectories()) {
                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                            .forEach(path -> result.add(new ArchiveEntry(path.toString(), path.getFileName().toString())));
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    public record ArchiveEntry(String fullPath, String fileName) {
    }

    public static void extractEntry(Path archive,
                                    String entryFullPath,
                                    Path destination) throws IOException {
        if (archive == null || entryFullPath == null || destination == null) {
            throw new IOException("Invalid archive extraction arguments");
        }
        try (FileSystem fs = FileSystems.newFileSystem(archive)) {
            Path entry = fs.getPath(entryFullPath);
            if (!Files.exists(entry) || Files.isDirectory(entry)) {
                throw new IOException("Archive entry not found: " + entryFullPath);
            }
            Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
