package eu.nurkert.neverUp2Late.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
public class DownloadHandler {

    public static void downloadJar(String downloadUrl, String destinationPath) throws IOException {
        Path destination = Paths.get(destinationPath);

        // Lösche vorhandene Datei, falls sie existiert
        if (Files.exists(destination)) {
            Files.delete(destination);
        }

        // Öffne Verbindung zum Download-Link und lade die Datei herunter
        try (InputStream in = new URL(downloadUrl).openStream()) {
            Files.copy(in, destination);
        }
    }
}
