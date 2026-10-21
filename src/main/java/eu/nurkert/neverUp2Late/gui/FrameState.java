package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Theme;
import org.bukkit.Material;

/**
 * The colour of a screen's border: the state of things readable before a
 * single item has been hovered.
 */
enum FrameState {
    UP_TO_DATE,
    UPDATES_WAITING,
    FAILING,
    /** Screens that are about a decision rather than about plugins. */
    NEUTRAL;

    /** The frame for a screen about one plugin. */
    static FrameState of(PluginStatus status) {
        return switch (status) {
            case FAILING -> FAILING;
            case UPDATE_READY -> UPDATES_WAITING;
            case UP_TO_DATE, NOT_LINKED -> UP_TO_DATE;
        };
    }

    Material pane() {
        return switch (this) {
            case UP_TO_DATE -> Material.LIME_STAINED_GLASS_PANE;
            case UPDATES_WAITING -> Material.YELLOW_STAINED_GLASS_PANE;
            case FAILING -> Material.RED_STAINED_GLASS_PANE;
            case NEUTRAL -> Material.GRAY_STAINED_GLASS_PANE;
        };
    }

    /** The one-line explanation shown on a header's summary item. */
    String describe() {
        return switch (this) {
            case UP_TO_DATE -> Theme.SUCCESS + Theme.CHECK + " Everything is up to date";
            case UPDATES_WAITING -> Theme.WARNING + Theme.UP + " Updates are waiting for a restart";
            case FAILING -> Theme.ERROR + Theme.WARN + " An update source is failing";
            case NEUTRAL -> Theme.MUTED + Theme.DOT;
        };
    }
}
