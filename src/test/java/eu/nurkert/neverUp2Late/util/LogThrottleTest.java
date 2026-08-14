package eu.nurkert.neverUp2Late.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogThrottleTest {

    private final List<LogRecord> records = new ArrayList<>();
    private LogThrottle throttle;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("nu2l-throttle-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // nothing to flush
            }

            @Override
            public void close() {
                // nothing to close
            }
        });
        throttle = new LogThrottle(logger);
    }

    @Test
    void reportsFirstOccurrenceAtRequestedLevel() {
        throttle.log("io:paper", Level.WARNING, "I/O error while updating {0}", "paper");

        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
    }

    @Test
    void demotesIdenticalRepeatsToFine() {
        for (int i = 0; i < 3; i++) {
            throttle.log("io:paper", Level.WARNING, "I/O error while updating {0}", "paper");
        }

        assertEquals(3, records.size(), "every call still logs, only the level changes");
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals(Level.FINE, records.get(1).getLevel());
        assertEquals(Level.FINE, records.get(2).getLevel());
    }

    @Test
    void reportsAgainWhenTheConditionChanges() {
        throttle.log("io:paper", Level.WARNING, "I/O error while updating {0}: {1}", "paper", "timeout");
        throttle.log("io:paper", Level.WARNING, "I/O error while updating {0}: {1}", "paper", "timeout");
        throttle.log("io:paper", Level.WARNING, "I/O error while updating {0}: {1}", "paper", "connection reset");

        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals(Level.FINE, records.get(1).getLevel());
        assertEquals(Level.WARNING, records.get(2).getLevel(), "a different cause is news again");
    }

    @Test
    void keysAreIndependent() {
        throttle.log("io:paper", Level.WARNING, "I/O error while updating {0}", "paper");
        throttle.log("io:geyser", Level.WARNING, "I/O error while updating {0}", "geyser");

        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals(Level.WARNING, records.get(1).getLevel());
    }

    @Test
    void clearMakesTheNextOccurrenceLoudAgain() {
        throttle.log("io:paper", Level.WARNING, "I/O error while updating {0}", "paper");

        assertTrue(throttle.clear("io:paper"), "clear reports that a state existed");
        assertFalse(throttle.clear("io:paper"), "clearing twice reports nothing to clear");

        throttle.log("io:paper", Level.WARNING, "I/O error while updating {0}", "paper");
        assertEquals(Level.WARNING, records.get(1).getLevel());
    }

    @Test
    void reportsAPersistentConditionAgainAfterAWhile() {
        // A source that keeps failing must not become quieter than the startup
        // messages: it goes silent for a while, then says so again.
        long[] now = {0L};
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger("nu2l-throttle-repeat-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        LogThrottle repeating = new LogThrottle(logger, 1000L, () -> now[0]);

        repeating.log("io:paper", Level.WARNING, "still broken");
        repeating.log("io:paper", Level.WARNING, "still broken");
        now[0] = 1500L;
        repeating.log("io:paper", Level.WARNING, "still broken");

        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals(Level.FINE, records.get(1).getLevel(), "quiet while it is fresh");
        assertEquals(Level.WARNING, records.get(2).getLevel(), "loud again once the window passed");
    }

    @Test
    void throwablesAreThrottledByTheirDescription() {
        throttle.log("boom:paper", Level.SEVERE, "Unexpected error", new IllegalStateException("broken"));
        throttle.log("boom:paper", Level.SEVERE, "Unexpected error", new IllegalStateException("broken"));

        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertEquals(Level.FINE, records.get(1).getLevel());
        assertTrue(records.get(1).getThrown() instanceof IllegalStateException,
                "repeats keep the stack trace for debugging");
    }
}
