package eu.nurkert.neverUp2Late.gui;

/**
 * The overview's filter button. Each click moves to the next one, so the
 * order here is the order an operator cycles through.
 */
enum OverviewFilter {
    ALL("All plugins"),
    UPDATES_READY("Updates ready"),
    FAILING("Failing"),
    NOT_LINKED("Not linked");

    private final String label;

    OverviewFilter(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    boolean matches(PluginStatus status) {
        return switch (this) {
            case ALL -> true;
            case UPDATES_READY -> status == PluginStatus.UPDATE_READY;
            case FAILING -> status == PluginStatus.FAILING;
            case NOT_LINKED -> status == PluginStatus.NOT_LINKED;
        };
    }

    OverviewFilter next() {
        OverviewFilter[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    OverviewFilter previous() {
        OverviewFilter[] all = values();
        return all[(ordinal() + all.length - 1) % all.length];
    }
}
