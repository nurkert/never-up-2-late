package eu.nurkert.neverUp2Late.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessagesTest {

    @Test
    void aChoiceRunsItsSelectCommandWhenClicked() {
        Component choice = ChatMessages.choice(2, ChatColor.GRAY + "2. " + ChatColor.AQUA + "Demo.jar", "/nu2l select 2");

        assertEquals(ClickEvent.runCommand("/nu2l select 2"), choice.clickEvent());
        assertEquals("2. Demo.jar", PlainTextComponentSerializer.plainText().serialize(choice));
    }
}
