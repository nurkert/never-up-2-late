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

### Hangar sources

Projects hosted on [Hangar](https://hangar.papermc.io/) can be tracked by setting the source `type` to `hangar` and providing the project namespace inside the `options` block. The example below downloads the latest reviewed release for `ExampleAuthor/ExampleProject`:

```yaml
    - name: examplePlugin
      type: hangar
      target: plugins
      filename: "ExamplePlugin.jar"
      options:
        owner: ExampleAuthor       # or use "project: ExampleAuthor/ExampleProject"
        slug: ExampleProject
        platform: PAPER            # optional, defaults to PAPER
        ignoreUnstable: true       # optional, skip channels flagged as UNSTABLE (default true)
        allowedChannels: [Release] # optional whitelist of channel names
        installedPlugin: ExamplePlugin # optional, used to detect the installed version
```

### CurseForge sources

To download updates from [CurseForge](https://www.curseforge.com/) set the source `type` to `curseforge` and provide the numeric project id in the `options`. The fetcher can optionally filter by supported Minecraft versions or include beta builds when desired:

```yaml
    - name: examplePlugin
      type: curseforge
      target: plugins
      filename: "ExamplePlugin.jar"
      options:
        modId: 123456
        apiKey: "your-api-key"         # optional, but recommended for higher rate limits
        gameVersions: ["1.20.1"]        # optional list of accepted Minecraft versions
        releaseTypes: ["release"]       # optional, defaults to ["release"], can include "beta" or "alpha"
        installedPlugin: ExamplePlugin  # optional, used to detect the installed version
```

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

When using the `/nu2l install <url>` command you can control which GitHub asset is selected by appending query parameters to the release URL. `?asset=<text>` matches assets whose download URL contains the provided text (case-insensitive) while `?assetPattern=<regex>` accepts a full regular expression. Direct download links created from the "Download" buttons are also recognized and will automatically prefer the referenced asset on future updates.

### Jenkins sources

Self-hosted Jenkins instances can be queried by setting the source `type` to `jenkins`. Provide the base URL of the Jenkins instance and the job path (folders can be separated with `/`). When multiple artifacts are published you can either specify the exact `artifact` file name or a regular expression via `artifactPattern`.

```yaml
    - name: examplePlugin
      type: jenkins
      target: plugins
      filename: "ExamplePlugin.jar"
      options:
        baseUrl: https://jenkins.example.com/
        job: Plugins/ExamplePlugin
        artifactPattern: ".*ExamplePlugin.*\\.jar$"
        preferLastSuccessful: true       # optional, defaults to true
        versionSource: artifact          # optional (displayName, id, number, artifact)
        versionPattern: "ExamplePlugin-(.*)\\.jar" # optional regex to extract the version
        installedPlugin: ExamplePlugin   # optional, used to detect the installed version
```

If neither `artifact` nor `artifactPattern` are supplied, the fetcher expects exactly one artifact to be present. The download URL is constructed from the selected artifact of the latest build returned by Jenkins.
