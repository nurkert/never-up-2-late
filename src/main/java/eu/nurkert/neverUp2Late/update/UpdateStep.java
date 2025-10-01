package eu.nurkert.neverUp2Late.update;

/**
 * Represents a discrete unit of work that can be executed within an
 * {@link UpdateJob}. Implementations may decide to cancel further execution by
 * calling {@link UpdateContext#cancel(String)} on the provided context.
 */
@FunctionalInterface
public interface UpdateStep {

    /**
     * Executes the step using the given {@link UpdateContext}.
     *
     * @param context shared update context that carries state between steps
     * @throws Exception if execution fails
     */
    void execute(UpdateContext context) throws Exception;
}
