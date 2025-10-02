package eu.nurkert.neverUp2Late.fetcher;

import java.util.ArrayList;
import java.util.List;
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
        return build(assetName, List.of());
    }

    public static String build(String assetName, List<String> otherAssets) {
        String value = Objects.requireNonNull(assetName, "assetName");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("assetName must not be blank");
        }

        List<VersionToken> tokens = extractTokens(value);
        boolean[] wildcard = new boolean[tokens.size()];
        for (int i = 0; i < wildcard.length; i++) {
            wildcard[i] = true;
        }

        String pattern = buildPattern(value, tokens, wildcard);

        if (!conflicts(pattern, value, otherAssets)) {
            return pattern;
        }

        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (!wildcard[i]) {
                continue;
            }
            wildcard[i] = false;
            pattern = buildPattern(value, tokens, wildcard);
            if (!conflicts(pattern, value, otherAssets)) {
                return pattern;
            }
        }

        return pattern;
    }

    private static List<VersionToken> extractTokens(String value) {
        Matcher matcher = VERSION_TOKEN.matcher(value);
        List<VersionToken> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(new VersionToken(matcher.start(), matcher.end()));
        }
        return tokens;
    }

    private static String buildPattern(String value, List<VersionToken> tokens, boolean[] wildcard) {
        StringBuilder builder = new StringBuilder("(?i)^");
        int lastEnd = 0;
        for (int i = 0; i < tokens.size(); i++) {
            VersionToken token = tokens.get(i);
            if (token.start() > lastEnd) {
                builder.append(Pattern.quote(value.substring(lastEnd, token.start())));
            }
            if (wildcard[i]) {
                builder.append("\\d+(?:[._+-]\\d+)*");
            } else {
                builder.append(Pattern.quote(value.substring(token.start(), token.end())));
            }
            lastEnd = token.end();
        }
        if (lastEnd < value.length()) {
            builder.append(Pattern.quote(value.substring(lastEnd)));
        }
        builder.append('$');
        return builder.toString();
    }

    private static boolean conflicts(String pattern,
                                     String selected,
                                     List<String> otherAssets) {
        if (otherAssets == null || otherAssets.isEmpty()) {
            return false;
        }

        Pattern compiled = Pattern.compile(pattern);
        String selectedNormalized = selected.trim();

        for (String candidate : otherAssets) {
            if (candidate == null) {
                continue;
            }
            String normalized = candidate.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.equalsIgnoreCase(selectedNormalized)) {
                continue;
            }
            if (compiled.matcher(normalized).matches()) {
                return true;
            }
        }

        return false;
    }

    private record VersionToken(int start, int end) {
    }
}
