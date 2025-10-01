package eu.nurkert.neverUp2Late.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a composed update workflow consisting of ordered
 * {@link UpdateStep UpdateSteps}.
 */
public class UpdateJob {

    private final List<UpdateStep> steps = new ArrayList<>();

    public UpdateJob addStep(UpdateStep step) {
        steps.add(step);
        return this;
    }

    public List<UpdateStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public void run(UpdateContext context) throws Exception {
        for (UpdateStep step : steps) {
            if (context.isCancelled()) {
                break;
            }
            step.execute(context);
        }
    }
}
