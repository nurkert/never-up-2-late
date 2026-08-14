package eu.nurkert.neverUp2Late.update;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparator is used to sort and to decide "is this newer", so it has to be
 * a valid ordering - not merely right on the pairs somebody thought of.
 */
class VersionComparatorOrderingTest {

    private static final List<String> SAMPLES = List.of(
            "1.0", "1.0.0", "1.0.1", "1.2.3", "1.10.0", "2.0.0",
            "v2.0.0", "2.0.0-beta", "2.0.0-rc.1", "2.0.0-rc.2", "2.0.1+build.7",
            "", "garbage", "20240115123045999999999");

    private final VersionComparator comparator = new VersionComparator();

    @Test
    void isAntisymmetric() {
        for (String left : SAMPLES) {
            for (String right : SAMPLES) {
                int forward = Integer.signum(comparator.compare(left, right));
                int backward = Integer.signum(comparator.compare(right, left));
                assertEquals(forward, -backward,
                        "compare(" + left + ", " + right + ") must mirror the reverse");
            }
        }
    }

    @Test
    void isTransitive() {
        for (String a : SAMPLES) {
            for (String b : SAMPLES) {
                for (String c : SAMPLES) {
                    if (comparator.compare(a, b) <= 0 && comparator.compare(b, c) <= 0) {
                        assertTrue(comparator.compare(a, c) <= 0,
                                a + " <= " + b + " <= " + c + " must imply " + a + " <= " + c);
                    }
                }
            }
        }
    }

    @Test
    void sortsWithoutThrowing() {
        List<String> sorted = new ArrayList<>(SAMPLES);
        sorted.sort(comparator::compare);
        assertEquals(SAMPLES.size(), sorted.size());
        assertTrue(sorted.indexOf("1.0.1") < sorted.indexOf("2.0.0"));
        assertTrue(sorted.indexOf("2.0.0-beta") < sorted.indexOf("2.0.0"));
    }
}
