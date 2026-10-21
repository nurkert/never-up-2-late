package eu.nurkert.neverUp2Late.gui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiLayoutTest {

    @Test
    void contentSlotsAndIndicesAreInverse() {
        for (int index = 0; index < GuiLayout.CONTENT_SIZE; index++) {
            int slot = GuiLayout.contentSlot(index);
            assertFalse(GuiLayout.isFrame(slot), "content slot " + slot + " lies in the frame");
            assertEquals(index, GuiLayout.contentIndex(slot));
        }
        assertEquals(-1, GuiLayout.contentIndex(GuiLayout.SUMMARY_SLOT));
        assertEquals(-1, GuiLayout.contentIndex(GuiLayout.CLOSE_SLOT));
        assertThrows(IndexOutOfBoundsException.class, () -> GuiLayout.contentSlot(GuiLayout.CONTENT_SIZE));
    }

    @Test
    void everyControlSitsInTheFrameOnItsOwnSlot() {
        List<Integer> controls = List.of(GuiLayout.SUMMARY_SLOT, GuiLayout.TOOL_LEFT_SLOT,
                GuiLayout.TOOL_RIGHT_SLOT, GuiLayout.TOOL_FAR_RIGHT_SLOT, GuiLayout.BACK_SLOT,
                GuiLayout.PREVIOUS_SLOT, GuiLayout.PAGE_SLOT, GuiLayout.NEXT_SLOT, GuiLayout.FILTER_SLOT,
                GuiLayout.SEARCH_SLOT, GuiLayout.CLOSE_SLOT);
        Set<Integer> seen = new HashSet<>();
        for (int slot : controls) {
            assertTrue(GuiLayout.isFrame(slot), "control " + slot + " is not in the frame");
            assertTrue(seen.add(slot), "two controls share slot " + slot);
        }
    }

    @Test
    void theEmptyNoticeSitsInTheContentArea() {
        assertTrue(GuiLayout.contentIndex(GuiLayout.EMPTY_NOTICE_SLOT) >= 0);
    }

    @Test
    void shortensLongNamesWithAnEllipsis() {
        assertEquals("Short", GuiLayout.shorten("Short", 10));
        assertEquals("ExactlyTen", GuiLayout.shorten("ExactlyTen", 10));
        assertEquals("ABCDEFGHI…", GuiLayout.shorten("ABCDEFGHIJKLMNOP", 10));
        assertEquals("", GuiLayout.shorten(null, 10));
        assertEquals("", GuiLayout.shorten("abc", 0));
    }
}
