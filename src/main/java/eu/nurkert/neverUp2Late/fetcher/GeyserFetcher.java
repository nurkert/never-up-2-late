package eu.nurkert.neverUp2Late.fetcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GeyserFetcher is responsible for fetching the latest Geyser Spigot build
 * compatible with the latest Minecraft version from the Modrinth API.
 */
public class GeyserFetcher implements UpdateFetcher {

    // Define the API endpoint URL
    private static final String API_URL = "https://api.modrinth.com/v2/project/geyser/version";

    // Fields to store the latest build information
    private String latestVersionNumber;
    private int latestBuildNumber;
    private String latestDownloadUrl;

    /**
     * Loads the latest Geyser build information compatible with the latest Minecraft version.
     *
     * @throws Exception If an error occurs during the HTTP request or data processing.
     */
    @Override
    public void loadLatestBuildInfo() throws Exception {
        // Fetch the API response
        String jsonResponse = fetchApiResponse(API_URL);

        // Process the API response to extract the latest build information
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
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        // Set the request method to GET
        connection.setRequestMethod("GET");

        try {
            // Check the HTTP response code
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("HTTP GET Request Failed with Error code: " + responseCode);
            }

            // Read the response from the input stream
            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String inputLine;
                StringBuilder response = new StringBuilder();

                // Append each line of the response
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }

                // Return the response as a string
                return response.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Processes the JSON response from the API to find the latest Geyser Spigot build
     * compatible with the latest Minecraft game version.
     *
     * @param jsonResponse The JSON response from the API as a string.
     */
    private void processApiResponse(String jsonResponse) {
        // Remove all whitespace from the JSON response for easier parsing
        String jsonResponseNoWhitespace = jsonResponse.replaceAll("\\s", "");

        // Remove the outer square brackets to get the array content
        String jsonArrayString = jsonResponseNoWhitespace.substring(1, jsonResponseNoWhitespace.length() - 1);

        // Split the array content into individual JSON objects
        List<String> jsonObjects = extractJsonObjects(jsonArrayString);

        // List to store all builds
        List<Build> builds = new ArrayList<>();

        // Set to store all game versions
        Set<String> allGameVersions = new HashSet<>();

        // Parse each JSON object string into a Build object
        for (String jsonObjectString : jsonObjects) {
            // Extract 'status'
            String status = extractFieldValue(jsonObjectString, "\"status\":\"([^\"]*)\"");
            if (!"listed".equals(status)) {
                continue; // Skip builds that are not listed
            }

            // Extract 'game_versions'
            String gameVersionsString = extractFieldValue(jsonObjectString, "\"game_versions\":\\[([^\\]]*)\\]");
            List<String> gameVersions = extractStringArray(gameVersionsString);
            allGameVersions.addAll(gameVersions); // Collect all game versions

            // Extract 'loaders'
            String loadersString = extractFieldValue(jsonObjectString, "\"loaders\":\\[([^\\]]*)\\]");
            List<String> loaders = extractStringArray(loadersString);

            // Extract other necessary fields
            String datePublished = extractFieldValue(jsonObjectString, "\"date_published\":\"([^\"]*)\"");
            String versionNumber = extractFieldValue(jsonObjectString, "\"version_number\":\"([^\"]*)\"");
            String id = extractFieldValue(jsonObjectString, "\"id\":\"([^\"]*)\"");

            // Extract 'files' array and get the download URL
            String filesArrayString = extractFieldValue(jsonObjectString, "\"files\":\\[([^\\]]*)\\]");
            String downloadUrl = null;
            if (filesArrayString != null) {
                downloadUrl = extractFieldValue(filesArrayString, "\"url\":\"([^\"]*)\"");
            }

            // Create a Build object and add it to the list
            Build build = new Build();
            build.id = id;
            build.versionNumber = versionNumber;
            build.datePublished = datePublished;
            build.gameVersions = gameVersions;
            build.loaders = loaders;
            build.status = status;
            build.downloadUrl = downloadUrl;
            builds.add(build);
        }

        // Find the latest Minecraft version
        String latestMinecraftVersion = findLatestMinecraftVersion(allGameVersions);
        if (latestMinecraftVersion == null) {
            System.out.println("No game versions found in the builds.");
            return;
        }

        // Filter builds that support the latest Minecraft version and have 'paper' or 'spigot' as loaders
        List<Build> matchingBuilds = new ArrayList<>();
        for (Build build : builds) {
            if (!build.gameVersions.contains(latestMinecraftVersion)) {
                continue;
            }
            if (!(build.loaders.contains("paper") || build.loaders.contains("spigot"))) {
                continue;
            }
            matchingBuilds.add(build);
        }

        // Find the most recent build among the matching builds
        Build latestBuild = null;
        for (Build build : matchingBuilds) {
            if (latestBuild == null) {
                latestBuild = build;
            } else {
                if (build.datePublished.compareTo(latestBuild.datePublished) > 0) {
                    latestBuild = build;
                }
            }
        }

        // Set the fields with the latest build information
        if (latestBuild != null) {
            this.latestVersionNumber = latestBuild.versionNumber;
            this.latestBuildNumber = extractBuildNumberFromVersion(latestBuild.versionNumber);
            this.latestDownloadUrl = latestBuild.downloadUrl;
        } else {
            System.out.println("No relevant build found for the latest Minecraft version.");
        }
    }

    /**
     * Extracts the build number from the version number string.
     *
     * @param versionNumber The version number string, e.g., "2.5.0-b713".
     * @return The build number as an integer, e.g., 713.
     */
    private int extractBuildNumberFromVersion(String versionNumber) {
        int index = versionNumber.lastIndexOf("-b");
        if (index != -1 && index + 2 < versionNumber.length()) {
            String buildNumberStr = versionNumber.substring(index + 2);
            try {
                return Integer.parseInt(buildNumberStr);
            } catch (NumberFormatException e) {
                System.err.println("Error parsing build number from version: " + versionNumber);
            }
        }
        return -1;
    }

    /**
     * Extracts the value of a field from a JSON string using a regular expression.
     *
     * @param json         The JSON string.
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

    /**
     * Extracts individual JSON object strings from the JSON array string.
     *
     * @param jsonArrayString The JSON array string without the outer brackets.
     * @return A list of JSON object strings.
     */
    private List<String> extractJsonObjects(String jsonArrayString) {
        List<String> jsonObjects = new ArrayList<>();
        // Split the JSON array string into individual JSON object strings
        String[] buildJsonStrings = jsonArrayString.split("\\},\\{");
        for (int i = 0; i < buildJsonStrings.length; i++) {
            String buildJsonString = buildJsonStrings[i];
            if (i == 0) {
                // First element, add '{' at the beginning and '}' at the end
                buildJsonString = buildJsonString + "}";
                buildJsonString = "{" + buildJsonString;
            } else if (i == buildJsonStrings.length - 1) {
                // Last element, add '{' at the beginning and '}' at the end
                buildJsonString = "{" + buildJsonString;
                buildJsonString = buildJsonString + "}";
            } else {
                // Middle elements, add '{' at the beginning and '}' at the end
                buildJsonString = "{" + buildJsonString + "}";
            }
            jsonObjects.add(buildJsonString);
        }
        return jsonObjects;
    }

    /**
     * Finds the latest Minecraft game version from a set of versions.
     *
     * @param gameVersions The set of game versions.
     * @return The latest game version as a string.
     */
    private String findLatestMinecraftVersion(Set<String> gameVersions) {
        String latestVersion = null;
        for (String version : gameVersions) {
            if (latestVersion == null || compareVersionStrings(version, latestVersion) > 0) {
                latestVersion = version;
            }
        }
        return latestVersion;
    }

    /**
     * Compares two version strings.
     *
     * @param v1 The first version string.
     * @param v2 The second version string.
     * @return A positive number if v1 > v2, negative if v1 < v2, zero if equal.
     */
    private int compareVersionStrings(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (num1 != num2) {
                return num1 - num2;
            }
        }
        return 0;
    }

    // Getters for the latest build information

    /**
     * Gets the latest version number.
     *
     * @return The latest version number as a string.
     */
    @Override
    public String getLatestVersion() {
        return latestVersionNumber;
    }

    /**
     * Gets the latest build number.
     *
     * @return The latest build number as an integer.
     */
    @Override
    public int getLatestBuild() {
        return latestBuildNumber;
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

    @Override
    public String getInstalledVersion() {
        return null;
    }


    /**
     * Inner class representing a build with relevant information.
     */
    private static class Build {
        String id;
        String versionNumber;
        String datePublished;
        List<String> gameVersions;
        List<String> loaders;
        String status;
        String downloadUrl;
    }
}