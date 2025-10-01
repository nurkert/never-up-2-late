![Alt text](https://raw.githubusercontent.com/nurkert/never-up-2-late/master/images/banner.png)

NeverUp2Late is a Minecraft plugin designed to automatically check for updates and download the latest versions of specified jars (Paper + Geyser). This ensures that your server is always up-to-date with the latest features and security patches.

## Installation

1. Download the latest release of NeverUp2Late from the [releases page](https://www.spigotmc.org/resources/neverup2late-automatically-keeps-paper-geyser-up-to-date.120768/history).
2. Place the `NeverUp2Late.jar` file in your server's `plugins` directory.
3. Start your server to generate the default configuration files.
4. Configure the plugin as needed in the `config.yml` file located in the `plugins/NeverUp2Late` directory.

## Configuration

The `config.yml` file allows you to configure the update interval, filenames and the list of update sources. Below is the default configuration with Paper and Geyser already set up:

```yaml
# The names of the jars as they are in the server directory
filenames:
  geyser: "Geyser-Spigot.jar"
  paper: "paper.jar"

# Check interval in minutes
updateInterval: 30

# Ignore unstable builds (legacy location, still respected if updates.ignoreUnstable is absent)
ignoreUnstable: true

updates:
  # Ignore unstable builds for fetchers that support filtering (e.g. Paper)
  ignoreUnstable: true

  # Configure the update sources that should be checked.
  # - name: identifier used for persistence and filename lookups
  # - type: either a simple alias (e.g. "paper") or the fully qualified UpdateFetcher class name
  # - target: "server" to place the jar next to the server executable or "plugins" for the plugins directory
  # - filename: optional override for the downloaded jar name (defaults to entries under filenames.<name>)
  sources:
    - name: paper
      type: paper
      target: server
      filename: "paper.jar"

    - name: geyser
      type: geyser
      target: plugins
      filename: "Geyser-Spigot.jar"
```

Additional sources can be registered by adding new entries to `updates.sources`. Point `type` to either the short alias (which resolves to a fetcher within this plugin) or the fully qualified class name of a custom `UpdateFetcher` implementation.

### GitHub release sources

To track releases that are published on GitHub, set the source `type` to `githubRelease` and provide the repository details inside the `options` block:

```yaml
    - name: examplePlugin
      type: githubRelease
      target: plugins
      filename: "ExamplePlugin.jar"
      options:
        owner: example
        repository: example-plugin
        assetPattern: ".*paper.*\\.jar$"   # optional regex filter applied to browser_download_url
        allowPrerelease: false               # optional, include pre-releases when true
        installedPlugin: "ExamplePlugin"    # optional, used to detect the currently installed version
```

If `assetPattern` is omitted the first asset with a download URL is used. When `installedPlugin` is configured the fetcher queries Bukkit's `PluginManager` to determine the installed version, otherwise it returns `null`.
