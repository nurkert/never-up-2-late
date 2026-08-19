package eu.nurkert.neverUp2Late.update;

import eu.nurkert.neverUp2Late.util.ArchiveUtils;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    private static final VersionComparator VERSION_COMPARATOR = new VersionComparator();

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

    /**
     * Checks that a staged artifact really is the plugin, and the version, that
     * the update claimed to be fetching.
     *
     * <p>Used for the update that has no second chance: NeverUp2Late replacing
     * its own jar. Everything else can be repaired by the plugin on the next
     * cycle, but a NeverUp2Late that does not load is a plugin folder nobody is
     * managing any more, and the operator finds out at the next restart.</p>
     *
     * <p>Reads the descriptor the way Bukkit's own loader does rather than by
     * matching lines, so the answer here is the answer the server will give,
     * and checks that the class named as the entry point is present and built
     * for a Java this server can actually load.</p>
     *
     * @param expectedVersion the version the release promised, or {@code null}
     *                        to accept whatever the jar declares
     * @return the reason the artifact must not be installed, or empty if it is
     *         what it claims to be
     */
    public static Optional<String> findIdentityProblem(Path staged,
                                                       String expectedPluginName,
                                                       String expectedVersion) {
        if (staged == null || expectedPluginName == null || expectedPluginName.isBlank()) {
            return Optional.empty();
        }

        try (ZipFile zip = new ZipFile(staged.toFile())) {
            ZipEntry descriptor = zip.getEntry("plugin.yml");
            if (descriptor == null) {
                return Optional.of("the downloaded file contains no plugin.yml, so it is not a plugin jar");
            }

            PluginDescriptionFile description;
            try (InputStream in = zip.getInputStream(descriptor)) {
                description = new PluginDescriptionFile(in);
            } catch (InvalidDescriptionException ex) {
                return Optional.of("the downloaded plugin.yml is not one the server could load: " + ex.getMessage());
            }

            if (!expectedPluginName.equalsIgnoreCase(description.getName())) {
                return Optional.of("the downloaded jar identifies as " + description.getName()
                        + ", not as " + expectedPluginName);
            }

            if (expectedVersion != null && !expectedVersion.isBlank()) {
                String declared = description.getVersion();
                if (declared == null || declared.isBlank()) {
                    return Optional.of("the downloaded jar declares no version, so it cannot be confirmed to be "
                            + expectedVersion);
                }
                if (VERSION_COMPARATOR.compare(declared, expectedVersion) != 0) {
                    return Optional.of("the release promised " + expectedVersion
                            + " but the jar inside it declares " + declared);
                }
            }

            return findUnloadableMainClass(zip, description.getMain());
        } catch (IOException ex) {
            return Optional.of("the downloaded file could not be read as a jar: " + ex.getMessage());
        }
    }

    /**
     * Confirms the entry point exists and was compiled for this runtime.
     *
     * <p>A jar built against a newer Java than the server runs is well formed,
     * declares everything correctly, and still fails at load with a
     * {@code UnsupportedClassVersionError}. That is the shape of "the server did
     * not come back up", and it is the one thing here a jar cannot simply
     * assert about itself.</p>
     */
    private static Optional<String> findUnloadableMainClass(ZipFile zip, String mainClass) {
        if (mainClass == null || mainClass.isBlank()) {
            return Optional.empty();
        }
        ZipEntry entry = zip.getEntry(mainClass.replace('.', '/') + ".class");
        if (entry == null) {
            return Optional.of("the downloaded jar names " + mainClass
                    + " as its entry point but does not contain that class");
        }
        try (DataInputStream in = new DataInputStream(zip.getInputStream(entry))) {
            if (in.readInt() != 0xCAFEBABE) {
                return Optional.of("the entry point " + mainClass + " in the downloaded jar is not a class file");
            }
            in.readUnsignedShort(); // minor
            int major = in.readUnsignedShort();
            int supported = Runtime.version().feature() + 44;
            if (major > supported) {
                return Optional.of("the downloaded jar was built for Java " + (major - 44)
                        + " but this server runs Java " + Runtime.version().feature()
                        + ", so it would fail to load");
            }
        } catch (IOException ex) {
            return Optional.of("the entry point " + mainClass + " in the downloaded jar could not be read");
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
