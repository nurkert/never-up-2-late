package eu.nurkert.neverUp2Late.gui;

/**
 * The grid every NU2L screen is drawn on, so a button never moves between screens.
 *
 * <pre>
 *  row 0  header   [ ][ ][T][ ][S][ ][T][T][ ]   S = summary, T = screen tools
 *  row 1-4 content  36 slots, filled left to right, top to bottom
 *  row 5  footer   [B][ ][ ][&lt;][P][&gt;][F][Q][X]   back, previous, page, next,
 *                                                 filter, search, close
 * </pre>
 *
 * <p>A screen leaves out what it has no use for - the overview has nowhere to
 * go back to, the details have no pages - and the frame shows through. What
 * it does show is always in the same place.</p>
 */
final class GuiLayout {

    static final int SIZE = 54;
    static final int ROW = 9;

    static final int SUMMARY_SLOT = 4;
    /** Screen-specific actions, either side of the summary. */
    static final int TOOL_LEFT_SLOT = 2;
    static final int TOOL_RIGHT_SLOT = 6;
    static final int TOOL_FAR_RIGHT_SLOT = 7;

    static final int FIRST_CONTENT_SLOT = ROW;
    static final int CONTENT_SIZE = 4 * ROW;

    static final int BACK_SLOT = 45;
    static final int PREVIOUS_SLOT = 48;
    static final int PAGE_SLOT = 49;
    static final int NEXT_SLOT = 50;
    static final int FILTER_SLOT = 51;
    static final int SEARCH_SLOT = 52;
    static final int CLOSE_SLOT = 53;

    /** Where a list puts its "nothing here" notice: the middle of the content area. */
    static final int EMPTY_NOTICE_SLOT = 22;

    private GuiLayout() {
    }

    /** Header and footer rows: the coloured frame. */
    static boolean isFrame(int slot) {
        return (slot >= 0 && slot < ROW) || (slot >= SIZE - ROW && slot < SIZE);
    }

    /** The slot for the {@code index}-th entry on a page. */
    static int contentSlot(int index) {
        if (index < 0 || index >= CONTENT_SIZE) {
            throw new IndexOutOfBoundsException("content index " + index);
        }
        return FIRST_CONTENT_SLOT + index;
    }

    /** Which entry on the page a slot shows, or -1 for a slot outside the content area. */
    static int contentIndex(int slot) {
        int index = slot - FIRST_CONTENT_SLOT;
        return index >= 0 && index < CONTENT_SIZE ? index : -1;
    }

    /**
     * Cuts {@code text} to {@code max} characters with an ellipsis. Inventory
     * titles do not wrap - a long plugin name would push the screen name off
     * the edge.
     */
    static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        if (max <= 0) {
            return "";
        }
        return text.substring(0, max - 1) + "…";
    }
}
