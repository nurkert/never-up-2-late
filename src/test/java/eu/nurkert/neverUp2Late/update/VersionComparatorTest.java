package eu.nurkert.neverUp2Late.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparator decides whether an update is required, so a wrong answer here
 * either freezes updates forever or re-installs the same build on every cycle.
 * The version strings below are the shapes that actually show up: GitHub tags,
 * plugin.yml values, Paper versions and Modrinth version numbers.
 */
class VersionComparatorTest {

    private final VersionComparator comparator = new VersionComparator();

    private void assertOlder(String older, String newer) {
        assertTrue(comparator.compare(older, newer) < 0, older + " should rank below " + newer);
        assertTrue(comparator.compare(newer, older) > 0, newer + " should rank above " + older);
    }

    private void assertSame(String left, String right) {
        assertEquals(0, comparator.compare(left, right), left + " should equal " + right);
        assertEquals(0, comparator.compare(right, left), right + " should equal " + left);
    }

    @Test
    void ordersOrdinaryReleases() {
        assertOlder("2.4.9", "2.4.10");
        assertOlder("1.2.3", "1.10.0");
        assertOlder("1.0", "1.0.1");
        assertOlder("1.9.9", "2.0.0");
    }

    @Test
    void treatsMissingTrailingSegmentsAsZero() {
        assertSame("1.0", "1.0.0");
        assertSame("2", "2.0.0.0");
    }

    @Test
    void ignoresATagStyleLeadingV() {
        // A GitHub release is tagged v2.4.6 while plugin.yml says 2.4.6. Reading
        // that as a downgrade made the updater re-install the same build forever.
        assertSame("v2.4.6", "2.4.6");
        assertOlder("2.4.5", "v2.4.6");
        assertOlder("v2.4.5", "2.4.6");
    }

    @Test
    void ranksPreReleasesBelowTheRelease() {
        assertOlder("1.0.0-SNAPSHOT", "1.0.0");
        assertOlder("1.0.0-beta", "1.0.0");
        assertOlder("1.0.0-rc1", "1.0.0");
        assertOlder("1.0.0-alpha", "1.0.0-beta");
        assertOlder("1.0.0-rc.1", "1.0.0-rc.2");
    }

    @Test
    void ignoresBuildMetadata() {
        assertSame("2.4.6+1.21", "2.4.6");
        assertOlder("2.4.6+1.21", "2.4.7");
    }

    @Test
    void handlesTheVersionShapesOfThisEcosystem() {
        assertOlder("1.20.1-R0.1-SNAPSHOT", "1.20.2");
        assertSame("1.20.1-R0.1-SNAPSHOT", "1.20.1-r0.1-snapshot");
        assertSame(" 2.4.6 ", "2.4.6");
        assertSame("1.02.0", "1.2.0");
    }

    @Test
    void staysTotalOnJunkInput() {
        // Never throw: these values come from remote APIs and foreign plugin.yml
        // files, and an exception here kills the whole update run.
        assertSame(null, null);
        assertOlder(null, "1.0.0");
        assertSame("", "");
        assertOlder("", "1.0.0");
        assertSame("garbage", "garbage");
        assertOlder("garbage", "1.0.0");
    }

    @Test
    void doesNotOverflowOnAbsurdlyLongNumbers() {
        // Date based versions are real, and Integer.parseInt used to throw here.
        assertOlder("1.0.0", "20240115123045999999999");
        assertSame("20240115123045999999999", "20240115123045999999999");
    }
}
