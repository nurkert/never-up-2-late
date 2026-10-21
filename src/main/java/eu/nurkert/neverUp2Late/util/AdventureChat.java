package eu.nurkert.neverUp2Late.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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

    static void send(CommandSender to, String legacyText, List<ChatMessages.Button> buttons) {
        ((Audience) to).sendMessage(message(legacyText, buttons));
    }

    static void sendChoice(CommandSender to, String legacyText, int number, String command) {
        ((Audience) to).sendMessage(choice(legacyText, number, command));
    }

    static Component message(String legacyText, List<ChatMessages.Button> buttons) {
        Component message = legacy(legacyText);
        for (ChatMessages.Button button : buttons) {
            ClickEvent click = button.command() != null
                    ? ClickEvent.runCommand(button.command())
                    : ClickEvent.openUrl(button.url());
            message = message.append(Component.text(" ")).append(Component.text(button.label(), NamedTextColor.AQUA)
                    .clickEvent(click)
                    .hoverEvent(HoverEvent.showText(Component.text(button.hover(), NamedTextColor.GRAY))));
        }
        return message;
    }

    static Component choice(String legacyText, int number, String command) {
        return legacy(legacyText)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to choose #" + number, NamedTextColor.GRAY)));
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }
}
