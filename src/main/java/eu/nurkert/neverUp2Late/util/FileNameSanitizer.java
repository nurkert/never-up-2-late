package eu.nurkert.neverUp2Late.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility methods for sanitising filenames used by NU2L when storing plugin artefacts.
 */
public final class FileNameSanitizer {

    private static final Pattern INVALID_CHARACTERS = Pattern.compile("[^a-zA-Z0-9._-]");

    private FileNameSanitizer() {
    }

    /**
     * Normalises the supplied input into a valid jar filename. Invalid characters are replaced by dashes and the
     * <code>.jar</code> extension is appended if it is missing. Returns {@code null} if the input is empty.
     */
    public static String sanitizeJarFilename(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String sanitized = INVALID_CHARACTERS.matcher(trimmed).replaceAll("-");
        if (!sanitized.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            sanitized = sanitized + ".jar";
        }
        return sanitized;
    }
}

