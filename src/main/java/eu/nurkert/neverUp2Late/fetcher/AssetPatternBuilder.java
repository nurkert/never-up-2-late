package eu.nurkert.neverUp2Late.fetcher;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility to create resilient regular expressions for GitHub release assets
 * based on a concrete asset name chosen by the user.
 */
public final class AssetPatternBuilder {

    private static final Pattern VERSION_TOKEN = Pattern.compile("\\d+(?:[._+-]\\d+)*");

    private AssetPatternBuilder() {
    }

    public static String build(String assetName) {
        String value = Objects.requireNonNull(assetName, "assetName");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("assetName must not be blank");
        }

        Matcher matcher = VERSION_TOKEN.matcher(value);
        StringBuilder builder = new StringBuilder("(?i)^");
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                builder.append(Pattern.quote(value.substring(lastEnd, matcher.start())));
            }
            builder.append("\\d+(?:[._+-]\\d+)*");
            lastEnd = matcher.end();
        }
        if (lastEnd < value.length()) {
            builder.append(Pattern.quote(value.substring(lastEnd)));
        }
        builder.append('$');
        return builder.toString();
    }
}
