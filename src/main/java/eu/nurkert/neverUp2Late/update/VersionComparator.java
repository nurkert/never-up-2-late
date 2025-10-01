package eu.nurkert.neverUp2Late.update;

/**
 * Utility responsible for comparing dotted version strings (e.g. 1.16.5).
 */
public class VersionComparator {

    public int compare(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < length; i++) {
            int v1 = i < parts1.length ? parse(parts1[i]) : 0;
            int v2 = i < parts2.length ? parse(parts2[i]) : 0;

            if (v1 < v2) {
                return -1;
            }
            if (v1 > v2) {
                return 1;
            }
        }
        return 0;
    }

    private int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
