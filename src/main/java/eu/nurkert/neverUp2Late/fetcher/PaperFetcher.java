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
 * compatible with the latest Minecraft version from the PaperMC API or website.
 */
public class PaperFetcher implements UpdateFetcher {

    // Define the API endpoint URL
    private static final String API_URL = "https://api.papermc.io/v2/projects/paper";

    // Fields to store the latest build information
    private String latestVersion;
    private int latestBuild;
    private String latestDownloadUrl;

    // Flag to determine whether to fetch only stable versions
    private boolean fetchStableVersions = true;

    /**
     * Default constructor that fetches only stable versions.
     */
    public PaperFetcher() {
    }

    /**
     * Constructor that allows specifying whether to fetch only stable versions.
     *
     * @param fetchStableVersions Set to true to fetch only stable versions, false to include unstable versions.
     */
    public PaperFetcher(boolean fetchStableVersions) {
        this.fetchStableVersions = fetchStableVersions;
    }

    /**
     * Loads the latest Paper build information.
     *
     * @throws Exception If an error occurs during the HTTP request or data processing.
     */
    @Override
    public void loadLatestBuildInfo() throws Exception {
        if (fetchStableVersions) {
            // Use initial implementation to fetch the latest stable version from the website
            this.latestVersion = getLatestStableVersionFromWebsite();
        } else {
            // Fetch the API response for the project
            String jsonResponse = fetchApiResponse(API_URL);

            // Process the API response to extract the latest version
            processApiResponse(jsonResponse);
        }

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
     * Fetches the latest stable version from the PaperMC website.
     *
     * @return The latest stable version as a string.
     * @throws Exception If an error occurs during the HTTP request or parsing.
     */
    private String getLatestStableVersionFromWebsite() throws Exception {
        String htmlContent = loadHtmlContent("https://papermc.io/downloads/paper");

        // Search for the <h2> element that contains "Get Paper"
        String h2TagStart = "<h2";
        int h2StartIndex = htmlContent.indexOf(h2TagStart);
        while (h2StartIndex != -1) {
            int h2EndIndex = htmlContent.indexOf("</h2>", h2StartIndex);
            if (h2EndIndex != -1) {
                String h2Content = htmlContent.substring(h2StartIndex, h2EndIndex + 5);
                if (h2Content.contains("Get") && h2Content.contains("Paper")) {
                    // Search for the <span class="text-blue-600"> within this <h2> element
                    String spanStartTag = "<span class=\"text-blue-600\">";
                    int spanStartIndex = h2Content.indexOf(spanStartTag);
                    if (spanStartIndex != -1) {
                        int versionStartIndex = spanStartIndex + spanStartTag.length();
                        int spanEndIndex = h2Content.indexOf("</span>", versionStartIndex);
                        if (spanEndIndex != -1) {
                            String version = h2Content.substring(versionStartIndex, spanEndIndex);
                            return version.trim();
                        }
                    }
                }
                // Search for the next <h2> element
                h2StartIndex = htmlContent.indexOf(h2TagStart, h2EndIndex);
            } else {
                break;
            }
        }
        // Return null if not found
        throw new Exception("Could not find the latest stable version on the website.");
    }

    /**
     * Loads the HTML content from a given URL.
     *
     * @param urlString The URL to load content from.
     * @return The HTML content as a string.
     * @throws Exception If an error occurs during the HTTP request.
     */
    private String loadHtmlContent(String urlString) throws Exception {
        StringBuilder result = new StringBuilder();
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        conn.setRequestMethod("GET");
        // Set the User-Agent header
        conn.setRequestProperty("User-Agent", "PaperFetcher");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        } finally {
            conn.disconnect();
        }
        return result.toString();
    }

    /**
     * Processes the JSON response from the API to find the latest version.
     * If fetchStableVersions is true, filters out unstable versions.
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

        // If fetchStableVersions is true, filter out unstable versions
        if (fetchStableVersions) {
            versions = filterStableVersions(versions);
            if (versions.isEmpty()) {
                throw new Exception("No stable versions found in API response.");
            }
        }

        // Get the latest version (the last in the list)
        this.latestVersion = versions.get(versions.size() - 1);
    }

    /**
     * Filters the list of versions to include only stable versions.
     *
     * @param versions The list of versions to filter.
     * @return A list containing only stable versions.
     */
    private List<String> filterStableVersions(List<String> versions) {
        List<String> stableVersions = new ArrayList<>();
        for (String version : versions) {
            if (isStableVersion(version)) {
                stableVersions.add(version);
            }
        }
        return stableVersions;
    }

    /**
     * Determines if a version string represents a stable version.
     *
     * @param version The version string to check.
     * @return True if the version is stable, false otherwise.
     */
    private boolean isStableVersion(String version) {
        // Unstable versions often contain '-' followed by 'pre', 'rc', etc.
        // Stable versions are in the format of major.minor.patch, e.g., '1.20.1'
        return !version.contains("-");
    }

    /**
     * Makes an HTTP GET request to the specified API endpoint and returns the response as a string.
     *
     * @param apiUrl The URL of the API endpoint.
     * @return The response from the API as a string.
     * @throws Exception If an error occurs during the HTTP request.
     */
    private String fetchApiResponse(String apiUrl) throws Exception {
        // Initialize a URL object with the API endpoint
        URL url = new URL(apiUrl);
        // Open an HTTP connection to the URL
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        // Set the request method to GET
        connection.setRequestMethod("GET");
        // Set the User-Agent header
        connection.setRequestProperty("User-Agent", "PaperFetcher");

        try {
            // Check the HTTP response code
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("HTTP GET Request Failed with Error code: " + responseCode);
            }

            // Read the response from the input stream
            StringBuilder response = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String inputLine;

                // Append each line of the response
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
            }

            // Return the response as a string
            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Extracts the value of a field from a string using a regular expression.
     *
     * @param text         The text to search.
     * @param regexPattern The regular expression pattern to match the field.
     * @return The extracted field value, or null if not found.
     */
    private String extractFieldValue(String text, String regexPattern) {
        Pattern pattern = Pattern.compile(regexPattern);
        Matcher matcher = pattern.matcher(text);
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
            list.add(element.trim());
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
     * Gets the installed Minecraft version from the server.
     *
     * @return The installed Minecraft version as a string.
     */
    @Override
    public String getInstalledVersion() {
        // Gets the full version string, e.g., "git-Paper-283 (MC: 1.16.5)"
        String fullVersion = Bukkit.getVersion();

        // Extracts the Minecraft version from the full version string
        String minecraftVersion = fullVersion.substring(fullVersion.indexOf("MC: ") + 4, fullVersion.length() - 1);

        return minecraftVersion;
    }
}