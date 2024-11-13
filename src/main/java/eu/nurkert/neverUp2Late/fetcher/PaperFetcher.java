package eu.nurkert.neverUp2Late.fetcher;

import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PaperFetcher is responsible for fetching the latest Paper build
 * compatible with the latest Minecraft version from the PaperMC API.
 */
public class PaperFetcher implements UpdateFetcher {

    // Define the API endpoint URL
    private static final String API_URL = "https://api.papermc.io/v2/projects/paper";

    // Fields to store the latest build information
    private String latestVersion;
    private int latestBuild;
    private String latestDownloadUrl;

    /**
     * Loads the latest Paper build information.
     *
     * @throws Exception If an error occurs during the HTTP request or data processing.
     */
    @Override
    public void loadLatestBuildInfo() throws Exception {
        // Fetch the API response for the project
        String jsonResponse = fetchApiResponse(API_URL);

        // Process the API response to extract the latest version
        processApiResponse(jsonResponse);
    }

    /**
     * Makes an HTTP GET request to the specified API endpoint and returns the JSON response as a string.
     *
     * @param apiUrl The URL of the API endpoint.
     * @return The JSON response from the API as a string.
     * @throws Exception If an error occurs during the HTTP request.
     */
    private String fetchApiResponse(String apiUrl) throws Exception {
        // Initialize a URL object with the API endpoint
        URL url = new URL(apiUrl);
        // Open an HTTP connection to the URL
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        // Set the request method to GET
        connection.setRequestMethod("GET");
        // Set the User-Agent header
        connection.setRequestProperty("User-Agent", "PaperFetcher");

        // Check the HTTP response code
        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("HTTP GET Request Failed with Error code: " + responseCode);
        }

        // Read the response from the input stream
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        String inputLine;
        StringBuilder response = new StringBuilder();

        // Append each line of the response
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        // Close the input stream
        in.close();

        // Return the response as a string
        return response.toString();
    }

    /**
     * Processes the JSON response from the API to find the latest Paper build
     * compatible with the latest Minecraft game version.
     *
     * @param jsonResponse The JSON response from the API as a string.
     * @throws Exception If an error occurs during data processing.
     */
    private void processApiResponse(String jsonResponse) throws Exception {
        // Extract the 'versions' array from the JSON response
        String versionsArrayString = extractFieldValue(jsonResponse, "\"versions\":\\[([^\\]]*)\\]");
        List<String> versions = extractStringArray(versionsArrayString);
        if (versions.isEmpty()) {
            throw new Exception("No versions found in API response.");
        }

        // Get the latest version (the last in the list)
        this.latestVersion = versions.get(versions.size() - 1);

        // Fetch builds for the latest version
        String buildsApiUrl = API_URL + "/versions/" + latestVersion;
        String buildsResponse = fetchApiResponse(buildsApiUrl);

        // Extract the 'builds' array from the builds response
        String buildsArrayString = extractFieldValue(buildsResponse, "\"builds\":\\[([^\\]]*)\\]");
        List<String> builds = extractStringArray(buildsArrayString);
        if (builds.isEmpty()) {
            throw new Exception("No builds found for version " + latestVersion);
        }

        // Get the latest build number (the last in the list)
        String latestBuildStr = builds.get(builds.size() - 1);
        this.latestBuild = Integer.parseInt(latestBuildStr);

        // Construct the download URL for the latest build
        this.latestDownloadUrl = API_URL + "/versions/" + latestVersion + "/builds/" + latestBuild + "/downloads/paper-" + latestVersion + "-" + latestBuild + ".jar";
    }

    /**
     * Extracts the value of a field from a JSON string using a regular expression.
     *
     * @param json        The JSON string.
     * @param regexPattern The regular expression pattern to match the field.
     * @return The extracted field value, or null if not found.
     */
    private String extractFieldValue(String json, String regexPattern) {
        Pattern pattern = Pattern.compile(regexPattern);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Extracts an array of strings from a JSON array string.
     *
     * @param arrayString The JSON array string.
     * @return A list of strings representing the array elements.
     */
    private List<String> extractStringArray(String arrayString) {
        List<String> list = new ArrayList<>();
        if (arrayString == null || arrayString.isEmpty()) {
            return list;
        }
        // Split the array elements
        String[] elements = arrayString.split(",");
        for (String element : elements) {
            // Remove leading and trailing quotes
            element = element.replaceAll("^\"|\"$", "");
            list.add(element);
        }
        return list;
    }

    // Getters for the latest build information

    /**
     * Gets the latest version.
     *
     * @return The latest version as a string.
     */
    @Override
    public String getLatestVersion() {
        return latestVersion;
    }

    /**
     * Gets the latest build number.
     *
     * @return The latest build number as an integer.
     */
    @Override
    public int getLatestBuild() {
        return latestBuild;
    }

    /**
     * Gets the download URL for the latest build.
     *
     * @return The latest download URL as a string.
     */
    @Override
    public String getLatestDownloadUrl() {
        return latestDownloadUrl;
    }

    /**
    *
     */
    @Override
    public String getInstalledVersion() {
        // Ruft die vollständige Version des Servers ab, z.B. "git-Spigot-3d850ec-809c399 (MC: 1.16.5)"
        String fullVersion = Bukkit.getVersion();

        // Extrahiert die Minecraft-Version aus dem vollständigen Versionsstring
        String minecraftVersion = fullVersion.substring(fullVersion.indexOf("MC: ") + 4, fullVersion.length() - 1);

        return minecraftVersion;
    }
}