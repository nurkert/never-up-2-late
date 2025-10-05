package eu.nurkert.neverUp2Late.fetcher.exception;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Signals that no release matching the currently configured or running Minecraft version
 * could be found. Callers may choose to ignore this warning and retry the fetch with
 * compatibility checks disabled.
 */
public class CompatibilityMismatchException extends IOException {

    private final String serverVersion;
    private final List<String> availableVersions;

    public CompatibilityMismatchException(String message,
                                          String serverVersion,
                                          Collection<String> availableVersions) {
        super(message);
        this.serverVersion = serverVersion;
        if (availableVersions == null || availableVersions.isEmpty()) {
            this.availableVersions = List.of();
        } else {
            this.availableVersions = List.copyOf(availableVersions);
        }
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public List<String> getAvailableVersions() {
        return availableVersions;
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
