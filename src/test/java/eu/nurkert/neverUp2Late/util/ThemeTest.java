package eu.nurkert.neverUp2Late.util;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeTest {

    @Test
    void writesHexColoursInTheFormatBukkitReads() {
        assertEquals("§x§3§d§d§6§d§0", Theme.hex(0x3DD6D0));
    }

    @Test
    void aGradientRunsFromTheFirstColourToTheLast() {
        String gradient = Theme.gradient("AB", 0x000000, 0xFFFFFF);

        assertEquals(Theme.hex(0x000000) + "A" + Theme.hex(0xFFFFFF) + "B", gradient);
        assertEquals("AB", ChatColor.stripColor(gradient));
    }
}
