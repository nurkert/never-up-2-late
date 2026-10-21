package eu.nurkert.neverUp2Late.gui;

/**
 * Where one managed plugin stands, reduced to the question an operator opens
 * the GUI for: does it need me?
 */
enum PluginStatus {
    /** Linked, and the last check found nothing newer than what runs. */
    UP_TO_DATE,
    /** A newer jar is on disk and waits for a restart. */
    UPDATE_READY,
    /** The last check of its source failed. */
    FAILING,
    /** No update source is linked, so nobody is looking for updates. */
    NOT_LINKED;

    /**
     * Decides the status from the facts the GUI can read. A failing check wins
     * over a waiting update: an update that is ready stays ready, while a
     * broken source silently stops delivering the next one.
     */
    static PluginStatus of(boolean linked, boolean lastCheckFailed, boolean updateReady) {
        if (!linked) {
            return NOT_LINKED;
        }
        if (lastCheckFailed) {
            return FAILING;
        }
        if (updateReady) {
            return UPDATE_READY;
        }
        return UP_TO_DATE;
    }
}
