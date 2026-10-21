package eu.nurkert.neverUp2Late.gui;

/**
 * Which slice of a list one page of a screen shows.
 *
 * <p>The overview used to grow until the chest was full and then silently
 * drop every plugin that did not fit. Every list now goes through this, so
 * nothing is ever hidden - only on a later page.</p>
 *
 * @param page      the zero-based page actually shown, clamped into range
 * @param pageCount how many pages there are; at least one, even for an empty list
 * @param fromIndex first list index on this page (inclusive)
 * @param toIndex   last list index on this page (exclusive)
 */
record Pagination(int page, int pageCount, int fromIndex, int toIndex) {

    /**
     * Pages {@code totalItems} entries into pages of {@code pageSize}. A
     * requested page outside the range lands on the nearest one, so a list
     * that shrank while a later page was open still shows something.
     */
    static Pagination of(int totalItems, int pageSize, int requestedPage) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        int total = Math.max(0, totalItems);
        int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.min(Math.max(0, requestedPage), pageCount - 1);
        int from = Math.min(total, page * pageSize);
        int to = Math.min(total, from + pageSize);
        return new Pagination(page, pageCount, from, to);
    }

    boolean hasPrevious() {
        return page > 0;
    }

    boolean hasNext() {
        return page < pageCount - 1;
    }

    int size() {
        return toIndex - fromIndex;
    }

    /** One-based, as an operator reads it: "Page 2 / 5". */
    String label() {
        return "Page " + (page + 1) + " / " + pageCount;
    }
}
