package eu.nurkert.neverUp2Late.gui;

import java.util.Locale;

/**
 * How a player is currently looking at the overview: which page, which
 * filter, which name search. Kept per player, so coming back from a plugin's
 * details lands where they left instead of on page one of everything.
 *
 * @param page   zero-based page; clamped when the screen is drawn
 * @param filter the active status filter
 * @param query  the name search, or {@code null} for none
 */
record OverviewView(int page, OverviewFilter filter, String query) {

    static final OverviewView DEFAULT = new OverviewView(0, OverviewFilter.ALL, null);

    OverviewView {
        filter = filter == null ? OverviewFilter.ALL : filter;
        query = query == null || query.isBlank() ? null : query.trim();
        page = Math.max(0, page);
    }

    boolean matches(String pluginName, PluginStatus status) {
        if (!filter.matches(status)) {
            return false;
        }
        if (query == null) {
            return true;
        }
        return pluginName != null
                && pluginName.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    boolean isNarrowed() {
        return filter != OverviewFilter.ALL || query != null;
    }

    OverviewView withPage(int newPage) {
        return new OverviewView(newPage, filter, query);
    }

    /** A different filter shows a different list, so it starts on its first page. */
    OverviewView withFilter(OverviewFilter newFilter) {
        return new OverviewView(0, newFilter, query);
    }

    OverviewView withQuery(String newQuery) {
        return new OverviewView(0, filter, newQuery);
    }
}
