package eu.nurkert.neverUp2Late.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * The Adventure half of {@link ChatMessages}. Only ever loaded on servers that
 * ship Adventure.
 */
final class AdventureChat {

    private AdventureChat() {
    }

    static boolean isAudience(CommandSender sender) {
        return sender instanceof Audience;
    }

    static void send(CommandSender to, String legacyText, String legacyHover, List<ChatMessages.Button> buttons) {
        ((Audience) to).sendMessage(message(legacyText, legacyHover, buttons));
    }

    static void sendChoice(CommandSender to, String legacyText, int number, String command) {
        ((Audience) to).sendMessage(choice(legacyText, number, command));
    }

    static void actionBar(CommandSender to, String legacyText) {
        ((Audience) to).sendActionBar(legacy(legacyText));
    }

    static Component message(String legacyText, String legacyHover, List<ChatMessages.Button> buttons) {
        Component text = legacy(legacyText);
        if (legacyHover != null) {
            text = text.hoverEvent(HoverEvent.showText(legacy(legacyHover)));
        }
        // The text and its buttons are siblings, so the hover on the text does
        // not leak onto the buttons, which carry their own.
        Component message = Component.empty().append(text);
        for (ChatMessages.Button button : buttons) {
            ClickEvent click = switch (button.kind()) {
                case RUN -> ClickEvent.runCommand(button.action());
                case SUGGEST -> ClickEvent.suggestCommand(button.action());
                case URL -> ClickEvent.openUrl(button.action());
            };
            message = message.append(Component.text(" ")).append(legacy(Theme.ACCENT + button.label())
                    .clickEvent(click)
                    .hoverEvent(HoverEvent.showText(legacy(Theme.MUTED + button.hover()))));
        }
        return message;
    }

    static Component choice(String legacyText, int number, String command) {
        return legacy(legacyText)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(legacy(Theme.MUTED + "Click to choose #" + number)));
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }
}
