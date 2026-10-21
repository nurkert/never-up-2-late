package eu.nurkert.neverUp2Late.gui;

import java.util.Collection;

/**
 * The counts behind the header of a screen and the colour of its frame.
 */
record PluginSummary(int total, int updatesReady, int failing, int notLinked) {

    static PluginSummary of(Collection<PluginStatus> statuses) {
        int updates = 0;
        int failing = 0;
        int notLinked = 0;
        for (PluginStatus status : statuses) {
            switch (status) {
                case UPDATE_READY -> updates++;
                case FAILING -> failing++;
                case NOT_LINKED -> notLinked++;
                default -> {
                }
            }
        }
        return new PluginSummary(statuses.size(), updates, failing, notLinked);
    }

    /** "12 plugins · 2 updates ready · 0 errors" - plain text, the caller colours it. */
    String headline() {
        return plural(total, "plugin", "plugins")
                + " · " + plural(updatesReady, "update ready", "updates ready")
                + " · " + plural(failing, "error", "errors");
    }

    /**
     * A failing source matters most - it stops all further updates without a
     * sound. Waiting updates come next; a plugin that was never linked is a
     * choice, not a problem, so it leaves the frame green.
     */
    FrameState frameState() {
        if (failing > 0) {
            return FrameState.FAILING;
        }
        if (updatesReady > 0) {
            return FrameState.UPDATES_WAITING;
        }
        return FrameState.UP_TO_DATE;
    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
