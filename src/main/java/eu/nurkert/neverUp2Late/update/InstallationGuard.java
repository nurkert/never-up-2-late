package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.util.ArchiveUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The last check before a downloaded artifact is moved onto its destination.
 *
 * <p>Everything upstream of this point works with strings the updater does not
 * own: a file name copied into config.yml, a plugin name carried in a source
 * option, a version reported by a remote API. Any of them can be wrong, and
 * when they are, the move overwrites a jar that belongs to a different plugin
 * or leaves a second copy of the same plugin in the folder - which Bukkit
 * answers by refusing to load one of them.</p>
 *
 * <p>This guard asks the only authority that cannot be wrong: the
 * {@code plugin.yml} inside the jars themselves. It denies the move rather than
 * guessing, because a skipped update is an inconvenience while an overwritten
 * plugin is data loss.</p>
 */
public final class InstallationGuard {

    private InstallationGuard() {
    }

    /**
     * Checks whether {@code downloaded} may be moved onto {@code destination}.
     *
     * @param downloaded the artifact that was just fetched, still in staging
     * @param destination the path it is about to take
     * @return the reason the move must not happen, or empty if it is safe
     */
    public static Optional<String> findConflict(Path downloaded, Path destination) {
        if (downloaded == null || destination == null) {
            return Optional.empty();
        }

        String incomingName = pluginNameOf(downloaded);
        if (incomingName == null) {
            // Not a Bukkit plugin - a server jar, an archive, something the
            // guard has no opinion about. The remaining checks all compare
            // plugin identities and would have nothing to compare.
            return Optional.empty();
        }

        Path normalizedDestination = destination.toAbsolutePath().normalize();

        String occupantName = pluginNameOf(normalizedDestination);
        if (occupantName != null && !occupantName.equalsIgnoreCase(incomingName)) {
            return Optional.of("would overwrite " + normalizedDestination.getFileName()
                    + ", which belongs to " + occupantName + ", with " + incomingName);
        }

        return findExistingCopy(normalizedDestination, incomingName)
                .map(existing -> incomingName + " is already installed as " + existing.getFileName()
                        + "; writing " + normalizedDestination.getFileName()
                        + " would leave two copies of it in the folder");
    }

    /**
     * Finds a jar other than {@code destination} that declares {@code pluginName}.
     *
     * <p>Bukkit refuses to load two jars with the same plugin name, logs an
     * ambiguity error and skips one of them - and which one it keeps is not
     * something the operator picked.</p>
     */
    public static Optional<Path> findExistingCopy(Path destination, String pluginName) {
        if (destination == null || pluginName == null || pluginName.isBlank()) {
            return Optional.empty();
        }
        Path directory = destination.toAbsolutePath().normalize().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return Optional.empty();
        }

        Path normalizedDestination = destination.toAbsolutePath().normalize();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : jars) {
                Path normalized = jar.toAbsolutePath().normalize();
                if (normalized.equals(normalizedDestination)) {
                    continue;
                }
                if (pluginName.equalsIgnoreCase(pluginNameOf(normalized))) {
                    return Optional.of(normalized);
                }
            }
        } catch (IOException ignored) {
            // An unreadable directory is not evidence of a conflict.
        }
        return Optional.empty();
    }

    private static String pluginNameOf(Path jar) {
        return ArchiveUtils.getPluginInfo(jar)
                .map(ArchiveUtils.PluginInfo::name)
                .filter(name -> !name.isBlank())
                .orElse(null);
    }
}
