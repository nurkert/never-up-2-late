package eu.nurkert.neverUp2Late.util;

import org.bukkit.ChatColor;

/**
 * The colours every message and menu draws from, so the plugin reads as one
 * product instead of a dozen hand-picked {@code ChatColor}s.
 *
 * <p>Each colour is a legacy hex code ({@code §x§r§r§g§g§b§b}). Bukkit has
 * understood those since 1.16 on Spigot and Paper alike, in chat, item names,
 * lore and inventory titles, so nothing here depends on Adventure.</p>
 */
public final class Theme {

    /** Brand colour, for names, values and anything clickable. */
    public static final String ACCENT = hex(0x3DD6D0);
    /** The other end of the brand gradient. */
    public static final String ACCENT_DEEP = hex(0x3B82F6);
    public static final String SUCCESS = hex(0x4ADE80);
    public static final String WARNING = hex(0xFBBF24);
    public static final String ERROR = hex(0xF87171);
    /** Headings and list titles. */
    public static final String HEADING = hex(0xFDBA74);
    /** Menu titles. */
    public static final String TITLE = hex(0x8B5CF6);
    public static final String TITLE_LIGHT = hex(0xC4B5FD);
    /** Primary text. */
    public static final String TEXT = hex(0xF1F5F9);
    /** Secondary text: explanations, labels, hints. */
    public static final String MUTED = hex(0x94A3B8);
    /** Separators and the least important details. */
    public static final String SUBTLE = hex(0x64748B);

    /** Starts every message the plugin sends. */
    public static final String PREFIX = gradient("NU2L", 0x3DD6D0, 0x3B82F6) + SUBTLE + " » " + ChatColor.RESET;

    public static final String CHECK = "✔";
    public static final String CROSS = "✖";
    public static final String ARROW = "→";
    public static final String UP = "⬆";
    public static final String WARN = "⚠";
    public static final String DOT = "•";

    private Theme() {
    }

    /** The legacy code for an RGB colour, e.g. {@code 0x3DD6D0}. */
    public static String hex(int rgb) {
        String digits = String.format("%06x", rgb & 0xFFFFFF);
        StringBuilder code = new StringBuilder(14).append(ChatColor.COLOR_CHAR).append('x');
        for (char digit : digits.toCharArray()) {
            code.append(ChatColor.COLOR_CHAR).append(digit);
        }
        return code.toString();
    }

    /** Colours each letter of {@code text} along a gradient from {@code fromRgb} to {@code toRgb}. */
    public static String gradient(String text, int fromRgb, int toRgb) {
        int length = text.codePointCount(0, text.length());
        StringBuilder result = new StringBuilder(text.length() * 15);
        int[] codePoints = text.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            double t = length <= 1 ? 0 : (double) i / (length - 1);
            result.append(hex(mix(fromRgb, toRgb, t))).appendCodePoint(codePoints[i]);
        }
        return result.toString();
    }

    /** A thin horizontal rule for framing longer chat output. */
    public static String rule(int width) {
        return SUBTLE + ChatColor.STRIKETHROUGH + " ".repeat(width) + ChatColor.RESET;
    }

    private static int mix(int from, int to, double t) {
        int r = channel(from >> 16, to >> 16, t);
        int g = channel(from >> 8, to >> 8, t);
        int b = channel(from, to, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to, double t) {
        int a = from & 0xFF;
        int b = to & 0xFF;
        return (int) Math.round(a + (b - a) * t);
    }
}
