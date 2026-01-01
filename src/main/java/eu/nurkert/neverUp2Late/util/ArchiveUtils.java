package eu.nurkert.neverUp2Late.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Utility helpers for inspecting archive files such as ZIPs.
 */
public final class ArchiveUtils {

    private static final Pattern PLUGIN_NAME_PATTERN = Pattern.compile("(?m)^name:\s*['"]?([^'"\s]+)['"]?");

    private ArchiveUtils() {
    }

    /**
     * Extracts the plugin name from a JAR file's plugin.yml without loading it.
     *
     * @param jarPath path to the JAR file
     * @return the plugin name if found
     */
    public static Optional<String> getPluginName(Path jarPath) {
        if (jarPath == null || !Files.exists(jarPath) || !Files.isRegularFile(jarPath)) {
            return Optional.empty();
        }
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("plugin.yml");
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream is = zipFile.getInputStream(entry)) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Matcher matcher = PLUGIN_NAME_PATTERN.matcher(content);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
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