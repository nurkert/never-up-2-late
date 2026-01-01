package eu.nurkert.neverUp2Late.util;

import eu.nurkert.neverUp2Late.update.VersionComparator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
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

    private static final Pattern PLUGIN_NAME_PATTERN = Pattern.compile("(?m)^name:\s*['"]?([^'\"\s]+)['"]?");
    private static final Pattern PLUGIN_VERSION_PATTERN = Pattern.compile("(?m)^version:\s*['"]?([^'\"\s]+)['"]?");

    private ArchiveUtils() {
    }

    public record PluginInfo(String name, String version, Path path) {
    }

    /**
     * Extracts the plugin name and version from a JAR file's plugin.yml without loading it.
     *
     * @param jarPath path to the JAR file
     * @return the plugin info if found
     */
    public static Optional<PluginInfo> getPluginInfo(Path jarPath) {
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
                Matcher nameMatcher = PLUGIN_NAME_PATTERN.matcher(content);
                Matcher versionMatcher = PLUGIN_VERSION_PATTERN.matcher(content);
                
                if (nameMatcher.find()) {
                    String name = nameMatcher.group(1);
                    String version = versionMatcher.find() ? versionMatcher.group(1) : "0.0.0";
                    return Optional.of(new PluginInfo(name, version, jarPath));
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    /**
     * Scans a directory for all JARs identifying as the given plugin name.
     */
    public static List<PluginInfo> findJarsForPlugin(Path directory, String pluginName) {
        if (directory == null || pluginName == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<PluginInfo> matches = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path entry : stream) {
                getPluginInfo(entry).ifPresent(info -> {
                    if (info.name().equalsIgnoreCase(pluginName)) {
                        matches.add(info);
                    }
                });
            }
        } catch (IOException ignored) {}
        return matches;
    }

    /**
     * Finds the "best" JAR from a list of plugin infos (highest version, then newest file).
     */
    public static Optional<PluginInfo> findBestJar(List<PluginInfo> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        VersionComparator comparator = new VersionComparator();
        return candidates.stream().max((a, b) -> {
            int v = comparator.compare(a.version(), b.version());
            if (v != 0) return v;
            try {
                return Files.getLastModifiedTime(a.path()).compareTo(Files.getLastModifiedTime(b.path()));
            } catch (IOException e) {
                return 0;
            }
        });
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
