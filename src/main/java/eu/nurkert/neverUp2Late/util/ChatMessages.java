package eu.nurkert.neverUp2Late.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Chat building blocks for messages a player can act on by clicking, instead
 * of reading a command off the screen and typing it back in.
 */
public final class ChatMessages {

    public static final Component PREFIX = Component.text("[", NamedTextColor.GRAY)
            .append(Component.text("nu2l", NamedTextColor.AQUA))
            .append(Component.text("] ", NamedTextColor.GRAY));

    private ChatMessages() {
    }

    /** Reads text formatted with {@code ChatColor}, as most messages here still are. */
    public static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    /** A button that runs {@code command} for whoever clicks it. */
    public static Component command(String label, String command, String hover) {
        return Component.text(label, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    /** A button that opens {@code url} in the player's browser. */
    public static Component link(String label, String url, String hover) {
        return Component.text(label, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    /**
     * One numbered entry of a list to choose from; clicking it runs
     * {@code command}.
     */
    public static Component choice(int number, String legacyLabel, String command) {
        return legacy(legacyLabel)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to choose #" + number, NamedTextColor.GRAY)));
    }
}
