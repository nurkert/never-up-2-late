package eu.nurkert.neverUp2Late.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
public class DownloadHandler {

    public static void downloadJar(String downloadUrl, String destinationPath) throws IOException {
        Path destination = Paths.get(destinationPath);

        URLConnection connection = new URL(downloadUrl).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);

        // Öffne Verbindung zum Download-Link und lade die Datei herunter
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
