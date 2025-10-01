package eu.nurkert.neverUp2Late.update;

/**
 * Callback interface that allows components to react to completed update
 * pipelines.
 */
@FunctionalInterface
public interface UpdateCompletionListener {
    void onUpdateCompleted(UpdateCompletedEvent event);
}
