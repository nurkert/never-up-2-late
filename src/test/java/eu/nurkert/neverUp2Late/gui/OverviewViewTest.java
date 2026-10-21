package eu.nurkert.neverUp2Late.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverviewViewTest {

    @Test
    void theDefaultViewShowsEverything() {
        for (PluginStatus status : PluginStatus.values()) {
            assertTrue(OverviewView.DEFAULT.matches("Anything", status));
        }
        assertFalse(OverviewView.DEFAULT.isNarrowed());
    }

    @Test
    void eachFilterShowsOnlyItsOwnStatus() {
        assertTrue(OverviewFilter.UPDATES_READY.matches(PluginStatus.UPDATE_READY));
        assertFalse(OverviewFilter.UPDATES_READY.matches(PluginStatus.UP_TO_DATE));
        assertTrue(OverviewFilter.FAILING.matches(PluginStatus.FAILING));
        assertFalse(OverviewFilter.FAILING.matches(PluginStatus.NOT_LINKED));
        assertTrue(OverviewFilter.NOT_LINKED.matches(PluginStatus.NOT_LINKED));
        assertFalse(OverviewFilter.NOT_LINKED.matches(PluginStatus.UPDATE_READY));
    }

    @Test
    void theFilterCyclesThroughEveryOptionAndBack() {
        OverviewFilter filter = OverviewFilter.ALL;
        for (int i = 0; i < OverviewFilter.values().length; i++) {
            filter = filter.next();
        }
        assertEquals(OverviewFilter.ALL, filter);
        assertEquals(OverviewFilter.NOT_LINKED, OverviewFilter.ALL.previous());
        assertEquals(OverviewFilter.UPDATES_READY, OverviewFilter.ALL.next());
    }

    @Test
    void searchMatchesPartOfTheNameIgnoringCase() {
        OverviewView view = OverviewView.DEFAULT.withQuery("  essen ");

        assertTrue(view.matches("EssentialsX", PluginStatus.UP_TO_DATE));
        assertFalse(view.matches("WorldEdit", PluginStatus.UP_TO_DATE));
        assertFalse(view.matches(null, PluginStatus.UP_TO_DATE));
        assertTrue(view.isNarrowed());
    }

    @Test
    void searchAndFilterMustBothMatch() {
        OverviewView view = OverviewView.DEFAULT.withFilter(OverviewFilter.UPDATES_READY).withQuery("edit");

        assertTrue(view.matches("WorldEdit", PluginStatus.UPDATE_READY));
        assertFalse(view.matches("WorldEdit", PluginStatus.UP_TO_DATE));
        assertFalse(view.matches("Essentials", PluginStatus.UPDATE_READY));
    }

    @Test
    void aBlankSearchClearsIt() {
        OverviewView view = OverviewView.DEFAULT.withQuery("x").withQuery("   ");

        assertNull(view.query());
        assertFalse(view.isNarrowed());
    }

    @Test
    void changingWhatIsShownStartsOnTheFirstPage() {
        OverviewView onPageThree = OverviewView.DEFAULT.withPage(2);

        assertEquals(0, onPageThree.withFilter(OverviewFilter.FAILING).page());
        assertEquals(0, onPageThree.withQuery("abc").page());
        assertEquals(2, onPageThree.page());
    }
}
