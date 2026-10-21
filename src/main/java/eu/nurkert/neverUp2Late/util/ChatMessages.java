package eu.nurkert.neverUp2Late.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

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

    public static final String PREFIX = ChatColor.GRAY + "[" + ChatColor.AQUA + "nu2l" + ChatColor.GRAY + "] "
            + ChatColor.RESET;

    private static final boolean ADVENTURE = isPresent("net.kyori.adventure.audience.Audience");

    private ChatMessages() {
    }

    /**
     * A clickable part appended to a message.
     *
     * @param command run when clicked, or {@code null}
     * @param url     opened when clicked, or {@code null}
     */
    public record Button(String label, String hover, String command, String url) {

        public static Button command(String label, String command, String hover) {
            return new Button(label, hover, command, null);
        }

        public static Button link(String label, String url, String hover) {
            return new Button(label, hover, null, url);
        }

        String asPlainText() {
            return ChatColor.AQUA + label + ChatColor.GRAY + " (" + (command != null ? command : url) + ")";
        }
    }

    /** Sends {@code legacyText}, formatted with {@code ChatColor}, followed by the buttons. */
    public static void send(CommandSender to, String legacyText, Button... buttons) {
        if (clickable(to)) {
            AdventureChat.send(to, legacyText, List.of(buttons));
            return;
        }
        StringBuilder text = new StringBuilder(legacyText);
        for (Button button : buttons) {
            text.append(' ').append(button.asPlainText());
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
