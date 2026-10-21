package eu.nurkert.neverUp2Late.util;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Chat messages a player can act on by clicking, instead of reading a command
 * off the screen and typing it back in.
 *
 * <p>Clickable text needs Adventure, which Paper ships and plain Spigot does
 * not. Nothing in this class mentions an Adventure type, so it loads on any
 * server; {@link AdventureChat} is only touched once Adventure is known to be
 * there. Without it the same message arrives as plain text, with the command
 * or link written out.</p>
 */
public final class ChatMessages {

    public static final String PREFIX = Theme.PREFIX;

    private static final boolean ADVENTURE = isPresent("net.kyori.adventure.audience.Audience");

    private ChatMessages() {
    }

    /**
     * A clickable part appended to a message.
     *
     * @param hover  shown on mouse-over, may carry colour codes
     * @param action the command to run or suggest, or the URL to open
     */
    public record Button(String label, String hover, String action, Kind kind) {

        public enum Kind {
            /** Runs the command right away. */
            RUN,
            /** Puts the command into the chat box for the player to complete. */
            SUGGEST,
            /** Opens a web page. */
            URL
        }

        public static Button command(String label, String command, String hover) {
            return new Button(label, hover, command, Kind.RUN);
        }

        public static Button suggest(String label, String command, String hover) {
            return new Button(label, hover, command, Kind.SUGGEST);
        }

        public static Button link(String label, String url, String hover) {
            return new Button(label, hover, url, Kind.URL);
        }

        String asPlainText() {
            return Theme.ACCENT + label + Theme.MUTED + " (" + action + ")";
        }
    }

    /** Sends {@code legacyText}, formatted with {@code ChatColor}, followed by the buttons. */
    public static void send(CommandSender to, String legacyText, Button... buttons) {
        sendWithHover(to, legacyText, null, buttons);
    }

    /**
     * Like {@link #send}, with {@code legacyHover} shown when the player points
     * at the text itself - the place for details that would crowd the line.
     */
    public static void sendWithHover(CommandSender to, String legacyText, String legacyHover, Button... buttons) {
        boolean player = to instanceof Player;
        if (player && clickable(to)) {
            AdventureChat.send(to, legacyText, legacyHover, List.of(buttons));
            return;
        }
        // Nobody clicks in a console: commands would only be noise there, but
        // a link is still worth having.
        StringBuilder text = new StringBuilder(legacyText);
        for (Button button : buttons) {
            if (player || button.kind() == Button.Kind.URL) {
                text.append(' ').append(button.asPlainText());
            }
        }
        to.sendMessage(text.toString());
    }

    /** Sends one entry of a list to choose from; clicking it runs {@code command}. */
    public static void sendChoice(CommandSender to, String legacyText, int number, String command) {
        if (clickable(to)) {
            AdventureChat.sendChoice(to, legacyText, number, command);
            return;
        }
        to.sendMessage(legacyText);
    }

    static boolean adventureAvailable() {
        return ADVENTURE;
    }

    private static boolean clickable(CommandSender to) {
        return ADVENTURE && AdventureChat.isAudience(to);
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, ChatMessages.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }
}
