package eu.nurkert.neverUp2Late.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares the version strings that update sources and plugins report.
 *
 * <p>The strings arrive in wildly different shapes: a GitHub tag ({@code v2.4.6}),
 * a plugin.yml value ({@code 2.4.6}, {@code 1.0-SNAPSHOT}, {@code 2.4.6 (build 45)}),
 * a Paper version ({@code 1.20.1-R0.1-SNAPSHOT}) or a Modrinth version number
 * ({@code 2.4.6+1.21}). A comparison must therefore never throw and never depend
 * on a single fixed layout.</p>
 *
 * <p>Both sides are split into numeric and alphabetic tokens and compared token by
 * token. Numbers compare numerically and are ranked above letters, so
 * {@code 1.0.0} outranks {@code 1.0.0-beta}; missing trailing tokens count as
 * zero, so {@code 1.0} and {@code 1.0.0} are equal. Build metadata after
 * {@code +} is ignored, as is a leading {@code v}.</p>
 */
public class VersionComparator {

    /**
     * @return a negative number if {@code version1} is older than {@code version2},
     *         zero if both describe the same version, a positive number otherwise
     */
    public int compare(String version1, String version2) {
        if (version1 == null || version2 == null) {
            if (version1 == null && version2 == null) {
                return 0;
            }
            return version1 == null ? -1 : 1;
        }

        List<Token> left = tokenize(version1);
        List<Token> right = tokenize(version2);

        int length = Math.max(left.size(), right.size());
        for (int i = 0; i < length; i++) {
            Token leftToken = i < left.size() ? left.get(i) : null;
            Token rightToken = i < right.size() ? right.get(i) : null;

            int result = compareTokens(leftToken, rightToken);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private int compareTokens(Token left, Token right) {
        if (left == null && right == null) {
            return 0;
        }
        // A side that ran out of tokens is a plain release. A trailing number is
        // an additional segment and compares against an implied zero, while a
        // trailing word marks a pre-release and therefore ranks below the release.
        if (left == null) {
            return right.numeric ? -Long.signum(right.number) : 1;
        }
        if (right == null) {
            return left.numeric ? Long.signum(left.number) : -1;
        }
        if (left.numeric && right.numeric) {
            return Long.compare(left.number, right.number);
        }
        if (left.numeric != right.numeric) {
            // 1.0.1 beats 1.0.rc - a number is always more specific than a word.
            return left.numeric ? 1 : -1;
        }
        return left.text.compareTo(right.text);
    }

    private List<Token> tokenize(String version) {
        String normalized = normalize(version);
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < normalized.length()) {
            char current = normalized.charAt(index);
            if (Character.isDigit(current)) {
                int start = index;
                while (index < normalized.length() && Character.isDigit(normalized.charAt(index))) {
                    index++;
                }
                tokens.add(Token.number(normalized, start, index));
            } else if (Character.isLetter(current)) {
                int start = index;
                while (index < normalized.length() && Character.isLetter(normalized.charAt(index))) {
                    index++;
                }
                tokens.add(Token.text(normalized.substring(start, index)));
            } else {
                // Separators (. - _ space ...) only delimit tokens.
                index++;
            }
        }
        return tokens;
    }

    private String normalize(String version) {
        String normalized = version.trim().toLowerCase(Locale.ROOT);

        // Semantic versioning build metadata says nothing about precedence.
        int metadata = normalized.indexOf('+');
        if (metadata >= 0) {
            normalized = normalized.substring(0, metadata);
        }

        // Tag names are commonly written as v2.4.6 while plugin.yml says 2.4.6.
        if (normalized.length() > 1 && normalized.charAt(0) == 'v' && Character.isDigit(normalized.charAt(1))) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static final class Token {

        private final boolean numeric;
        private final long number;
        private final String text;

        private Token(boolean numeric, long number, String text) {
            this.numeric = numeric;
            this.number = number;
            this.text = text;
        }

        static Token number(String source, int start, int end) {
            // Skip leading zeros so a very long run still fits, and saturate
            // rather than overflow on absurd values such as a date-based version.
            int begin = start;
            while (begin < end - 1 && source.charAt(begin) == '0') {
                begin++;
            }
            long value;
            if (end - begin > 18) {
                value = Long.MAX_VALUE;
            } else {
                value = Long.parseLong(source, begin, end, 10);
            }
            return new Token(true, value, null);
        }

        static Token text(String value) {
            return new Token(false, 0L, value);
        }
    }
}
