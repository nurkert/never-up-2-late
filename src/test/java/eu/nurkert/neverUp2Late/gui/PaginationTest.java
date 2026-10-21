package eu.nurkert.neverUp2Late.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationTest {

    @Test
    void anEmptyListStillHasOnePage() {
        Pagination pagination = Pagination.of(0, 36, 0);

        assertEquals(1, pagination.pageCount());
        assertEquals(0, pagination.size());
        assertEquals("Page 1 / 1", pagination.label());
        assertFalse(pagination.hasPrevious());
        assertFalse(pagination.hasNext());
    }

    @Test
    void aListThatFitsExactlyNeedsNoSecondPage() {
        Pagination pagination = Pagination.of(36, 36, 0);

        assertEquals(1, pagination.pageCount());
        assertEquals(36, pagination.size());
        assertFalse(pagination.hasNext());
    }

    @Test
    void oneMoreThanFitsOpensASecondPageInsteadOfDroppingIt() {
        Pagination first = Pagination.of(37, 36, 0);
        Pagination second = Pagination.of(37, 36, 1);

        assertEquals(2, first.pageCount());
        assertTrue(first.hasNext());
        assertEquals(36, second.fromIndex());
        assertEquals(37, second.toIndex());
        assertEquals(1, second.size());
        assertTrue(second.hasPrevious());
        assertFalse(second.hasNext());
        assertEquals("Page 2 / 2", second.label());
    }

    @Test
    void aPageBeyondTheEndLandsOnTheLastPage() {
        // The list shrank (a filter, a removed plugin) while a later page was open.
        Pagination pagination = Pagination.of(40, 36, 7);

        assertEquals(1, pagination.page());
        assertEquals(36, pagination.fromIndex());
    }

    @Test
    void aNegativePageLandsOnTheFirstPage() {
        assertEquals(0, Pagination.of(100, 36, -3).page());
    }

    @Test
    void rejectsAPageSizeOfZero() {
        assertThrows(IllegalArgumentException.class, () -> Pagination.of(10, 0, 0));
    }
}
