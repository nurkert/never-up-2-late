package eu.nurkert.neverUp2Late.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessagesTest {

    @Test
    void aChoiceRunsItsSelectCommandWhenClicked() {
        Component choice = AdventureChat.choice(ChatColor.GRAY + "2. " + ChatColor.AQUA + "Demo.jar", 2, "/nu2l select 2");

        assertEquals(ClickEvent.runCommand("/nu2l select 2"), choice.clickEvent());
        assertEquals("2. Demo.jar", PlainTextComponentSerializer.plainText().serialize(choice));
    }

    @Test
    void buttonsFollowTheTextAndCarryTheirAction() {
        Component message = AdventureChat.message("Update ready.", List.of(
                ChatMessages.Button.command("[Status]", "/nu2l status", "Show status"),
                ChatMessages.Button.link("[Changelog]", "https://example.com/1.0", "What changed")));

        assertEquals("Update ready. [Status] [Changelog]", PlainTextComponentSerializer.plainText().serialize(message));
        assertEquals(ClickEvent.openUrl("https://example.com/1.0"), message.children().get(3).clickEvent());
    }

    @Test
    void withoutAdventureTheActionIsWrittenOut() {
        assertEquals(ChatColor.AQUA + "[Status]" + ChatColor.GRAY + " (/nu2l status)",
                ChatMessages.Button.command("[Status]", "/nu2l status", "Show status").asPlainText());
    }
}
