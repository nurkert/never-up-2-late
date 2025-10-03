![NeverUp2Late banner](https://raw.githubusercontent.com/nurkert/never-up-2-late/master/images/banner.png)

# NeverUp2Late

NeverUp2Late keeps your Paper-based Minecraft server and its plugins permanently up to date. The plugin monitors all configured
sources on a schedule, downloads the most recent builds, replaces outdated JARs, and optionally reloads or restarts the server
once installations complete. You can manage update sources through YAML configuration, an interactive GUI, or the `/nu2l`
command that understands direct project URLs from popular hosting platforms.

## Highlights

- **Automated update pipeline** – Fetch, download, and installation steps run asynchronously with retry logic and persistent
  state tracking to avoid redundant downloads.【F:src/main/java/eu/nurkert/neverUp2Late/handlers/InstallationHandler.java†L13-L66】【F:src/main/java/eu/nurkert/neverUp2Late/persistence/UpdateStateRepository.java†L16-L118】
- **Wide provider support** – Built-in fetchers cover Paper, Geyser, Hangar, Modrinth, CurseForge, GitHub Releases, Jenkins,
  and fully custom implementations supplied by class name.【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L61-L160】【F:src/main/java/eu/nurkert/neverUp2Late/fetcher/GithubReleaseFetcher.java†L327-L375】
- **Quick installation** – `/nu2l <url>` analyses Hangar, Modrinth, GitHub, and Jenkins links, creates an update source on the
  fly, and immediately schedules the first installation run.【F:src/main/java/eu/nurkert/neverUp2Late/command/NeverUp2LateCommand.java†L32-L93】【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L73-L148】
- **Archive-aware downloads** – Releases that ship ZIP files can be inspected automatically; NeverUp2Late extracts the matching
  JAR from the archive or asks you to select the correct entry when multiple candidates exist.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L229-L360】
- **Interactive server GUI** – `/nu2l` without arguments opens a chest-based interface for linking update sources with plugins,
  changing per-plugin behaviour, renaming files, toggling updates, or launching manual searches.【F:src/main/java/eu/nurkert/neverUp2Late/gui/PluginOverviewGui.java†L43-L178】
- **First-run setup wizard** – Operators with `neverup2late.setup` are guided through an in-game onboarding flow that explains
  defaults, lets you review detected plugins, and activates the scheduler once finished.【F:src/main/java/eu/nurkert/neverUp2Late/setup/InitialSetupManager.java†L44-L130】
- **Lifecycle automation** – When enabled, NeverUp2Late reloads updated plugins or restarts the server after respecting a
  cooldown timer, so changes become active without manual intervention.【F:src/main/java/eu/nurkert/neverUp2Late/NeverUp2Late.java†L34-L97】【F:src/main/java/eu/nurkert/neverUp2Late/handlers/InstallationHandler.java†L70-L159】

## Installation

1. Download the latest release from the [SpigotMC resource page](https://www.spigotmc.org/resources/neverup2late-automatically-keeps-paper-geyser-up-to-date.120768/history).
2. Copy `NeverUp2Late.jar` into the `plugins` directory of your Paper (or compatible) server.
3. Start the server once so `config.yml`, state files, and the optional GUI resources are generated.【F:src/main/java/eu/nurkert/neverUp2Late/NeverUp2Late.java†L30-L105】
4. Adjust `plugins/NeverUp2Late/config.yml` to fit your environment.
5. Operators can complete the interactive setup wizard or open the GUI via `/nu2l` to review detected sources.【F:src/main/java/eu/nurkert/neverUp2Late/setup/InitialSetupManager.java†L82-L130】

### Requirements

- Minecraft server implementing the Bukkit API (Paper recommended) for Minecraft 1.21 or newer.【F:src/main/resources/plugin.yml†L1-L11】
- Java 17 or higher (matching the Paper 1.21 baseline).

## First-Time Setup

On first launch the plugin stays in setup mode until an operator completes the wizard. Eligible players receive a chat prompt and
an inventory interface that walks through:

1. Reviewing automatically created Paper and Geyser sources (legacy defaults).
2. Picking target directories (`server` or `plugins`) and filenames.
3. Confirming whether to restart immediately or later.

When the wizard finishes, NeverUp2Late records the completion status and starts the scheduled update loop. You can reopen the
wizard at any time with `/nu2l setup` while setup mode is active.【F:src/main/java/eu/nurkert/neverUp2Late/setup/InitialSetupManager.java†L64-L130】

## Commands

| Command | Description | Permission | Notes |
|---------|-------------|------------|-------|
| `/nu2l` | Opens the plugin overview GUI. | `neverup2late.gui.open` | Requires a player; shows managed plugins and install actions.【F:src/main/java/eu/nurkert/neverUp2Late/command/NeverUp2LateCommand.java†L32-L59】 |
| `/nu2l gui` | Explicitly opens the GUI. | `neverup2late.gui.open` | Alias for `/nu2l`. |
| `/nu2l <url>` | Runs quick installation for the provided URL. | `neverup2late.install` | Works from console or in-game; URLs must use HTTP(S).【F:src/main/java/eu/nurkert/neverUp2Late/command/NeverUp2LateCommand.java†L89-L120】 |
| `/nu2l select <number>` | Chooses an asset when multiple files are available. | `neverup2late.install` | Responds to prompts generated during quick install.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L188-L226】 |
| `/nu2l remove <name>` | Unregisters an update source and stops managing its file. | `neverup2late.gui.manage.remove` | Available from console and players.【F:src/main/java/eu/nurkert/neverUp2Late/command/NeverUp2LateCommand.java†L62-L84】 |
| `/nu2l setup` | Opens the first-run setup wizard. | `neverup2late.setup` | Players only; shows if setup is not completed.【F:src/main/java/eu/nurkert/neverUp2Late/command/NeverUp2LateCommand.java†L50-L68】 |

### Permission Overview

```
neverup2late.setup             # Access to the setup wizard
neverup2late.install           # Use /nu2l <url> and manage quick installs
neverup2late.gui.open          # Open the GUI
neverup2late.gui.manage        # Grant all GUI management permissions
neverup2late.gui.manage.lifecycle
neverup2late.gui.manage.link
neverup2late.gui.manage.settings
neverup2late.gui.manage.rename
neverup2late.gui.manage.remove
```

Default operators receive all permissions listed above.【F:src/main/resources/plugin.yml†L12-L33】

## Quick Install Workflow

1. **Analyse the URL** – NeverUp2Late parses Hangar, Modrinth, GitHub Releases, and Jenkins project URLs, derives a readable name,
   and prepares provider-specific options.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L135-L226】
2. **Link to an installed plugin** – The coordinator tries to match the download with an existing plugin to reuse version metadata
   for smarter update checks.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L149-L177】
3. **Fetch latest build info** – Version metadata and download URLs are loaded asynchronously; the chat shows progress and detected
   version numbers.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L200-L227】
4. **Handle archives or multiple assets** – If the release exposes several files or zipped distributions, the player is prompted to
   pick the desired asset or JAR inside the archive; regex patterns are stored automatically for future updates.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L229-L360】
5. **Schedule installation** – Once information is complete, the update handler copies the build into the configured directory and
   applies lifecycle rules (reload or restart).【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L205-L227】【F:src/main/java/eu/nurkert/neverUp2Late/handlers/InstallationHandler.java†L82-L159】

## Graphical Interface

The GUI mirrors all managed plugins and update sources in a paginated chest inventory. From here you can:

- Inspect plugin status (loaded, disabled, pending reload) at a glance.【F:src/main/java/eu/nurkert/neverUp2Late/gui/PluginOverviewGui.java†L118-L167】
- Enable, disable, load, or unload plugins when lifecycle management is active.【F:src/main/java/eu/nurkert/neverUp2Late/gui/PluginOverviewGui.java†L168-L206】
- Link existing plugins to update sources or accept automatic suggestions from Modrinth/Hangar search.【F:src/main/java/eu/nurkert/neverUp2Late/gui/PluginOverviewGui.java†L71-L113】
- Adjust per-plugin settings such as automatic updates, restart requirements, and filename retention (writes to `plugin-settings.yml`).【F:src/main/java/eu/nurkert/neverUp2Late/gui/PluginOverviewGui.java†L64-L87】【F:src/main/java/eu/nurkert/neverUp2Late/persistence/PluginUpdateSettingsRepository.java†L19-L82】
- Initiate quick installs or manual searches without leaving the interface.【F:src/main/java/eu/nurkert/neverUp2Late/gui/PluginOverviewGui.java†L88-L117】

If lifecycle management is disabled in the configuration, the GUI still lists update sources but hides actions that require
reloading plugins.【F:src/main/java/eu/nurkert/neverUp2Late/gui/PluginOverviewGui.java†L63-L87】【F:src/main/java/eu/nurkert/neverUp2Late/NeverUp2Late.java†L44-L73】

## Configuration

All configuration lives in `plugins/NeverUp2Late/config.yml`. The default layout looks like this:

```yaml
filenames:
  geyser: "Geyser-Spigot.jar"
  paper: "paper.jar"

updateInterval: 30
pluginLifecycle:
  autoManage: true

updates:
  ignoreUnstable: true
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

Key options:

- `updateInterval` – Minutes between scheduled update checks.【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L28-L118】
- `pluginLifecycle.autoManage` – Enables automatic plugin reloads and lifecycle controls. Set to `false` to keep manual restarts.【F:src/main/java/eu/nurkert/neverUp2Late/NeverUp2Late.java†L44-L73】
- `filenames.<name>` – Default filename for a source if `updates.sources[].filename` is omitted.【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L94-L155】
- `updates.ignoreUnstable` – Global default for filtering unstable/prerelease builds; individual sources can override it.【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L25-L95】
- `updates.sources` – Array of source descriptors, each providing `name`, `type`, `target` (`server` or `plugins`), optional
  `filename`, and an `options` block for provider-specific settings.【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L94-L155】

### Provider-Specific Options

| Type | Notable `options` |
|------|-------------------|
| `paper` | No additional fields required; auto-detects the installed Minecraft version. Supports `ignoreUnstable` or `allowUnstable` overrides.【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L94-L155】 |
| `geyser` | Preconfigured Modrinth integration with required build numbers; usually needs no extra options.【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L94-L155】 |
| `hangar` | Specify `owner` + `slug` or `project`. Optional: `platform`, `allowedChannels`, `ignoreUnstable` / `allowUnstable`, `requireReviewed`, `preferPinned`, `pageSize`, `maxPages`, and `installedPlugin` for version detection.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L135-L176】 |
| `modrinth` | Provide `project`; optionally filter by `loaders`, `statuses`, `versionTypes`, `gameVersions`, or `preferPrimaryFile`. `installedPlugin` links to an existing plugin.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L135-L176】 |
| `curseforge` | Requires `modId`. Optional rate-limit friendly `apiKey`, release type filters, game versions, and `installedPlugin`. |
| `githubRelease` | Needs `owner` and `repository`; supports `assetPattern`, `allowPrerelease`, `archiveEntryPattern`, and `installedPlugin` for archive extraction and version checks.【F:src/main/java/eu/nurkert/neverUp2Late/fetcher/GithubReleaseFetcher.java†L47-L159】 |
| `jenkins` | Configure `baseUrl` and `job`. Additional keys control artifact selection (`artifact`, `artifactPattern`), preferred build (`preferLastSuccessful`), and version parsing (`versionSource`, `versionPattern`, `installedPlugin`). |
| Custom class | Set `type` to the fully qualified class name of your `UpdateFetcher` implementation; all `options` values are passed to the constructor.【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L111-L160】 |

### Per-Plugin Behaviour (`plugin-settings.yml`)

NeverUp2Late writes individual preferences to `plugins/NeverUp2Late/plugin-settings.yml` whenever you tweak settings through the
GUI or API.

- `autoUpdate` – Toggle automatic update checks per plugin; disabling prevents scheduled downloads but manual runs still work.【F:src/main/java/eu/nurkert/neverUp2Late/persistence/PluginUpdateSettingsRepository.java†L38-L82】
- `behaviour` – Choose between `AUTO_RELOAD` (try to hot-reload after updates) and `REQUIRE_RESTART` (defer to server restart).【F:src/main/java/eu/nurkert/neverUp2Late/persistence/PluginUpdateSettingsRepository.java†L83-L136】
- `retainUpstreamFilename` – Preserve the upstream filename instead of renaming it to the configured target name.【F:src/main/java/eu/nurkert/neverUp2Late/persistence/PluginUpdateSettingsRepository.java†L37-L82】

## Update Cycle and Lifecycle Handling

The update handler continuously iterates over registered sources:

1. **Fetch metadata** – Each fetcher loads the latest build number and version string. Results are cached in `plugins.yml` to avoid
   downloading identical builds again.【F:src/main/java/eu/nurkert/neverUp2Late/persistence/UpdateStateRepository.java†L16-L118】
2. **Download artifacts** – Files are downloaded to a temporary location with retry logic and validated before being moved into the
   `server` or `plugins` directory.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L205-L227】
3. **Install and notify** – After copying, the installation handler executes post-update actions: plugin reload attempts, file path
   updates, and optional server restarts (with a one-hour cooldown that persists across restarts).【F:src/main/java/eu/nurkert/neverUp2Late/handlers/InstallationHandler.java†L70-L159】
4. **Lifecycle decisions** – If no players are online when an update finishes, actions run immediately; otherwise they are deferred
   until the server empties to avoid interrupting active sessions.【F:src/main/java/eu/nurkert/neverUp2Late/handlers/InstallationHandler.java†L48-L108】

## Persistence Layer

NeverUp2Late stores multiple files under `plugins/NeverUp2Late/`:

- `plugins.yml` – Tracks the last installed build and version per update source to prevent redundant downloads.【F:src/main/java/eu/nurkert/neverUp2Late/persistence/UpdateStateRepository.java†L16-L118】
- `plugin-settings.yml` – Remembers per-plugin preferences such as automatic updates and restart behaviour.【F:src/main/java/eu/nurkert/neverUp2Late/persistence/PluginUpdateSettingsRepository.java†L20-L99】
- `setup-state.yml` – Records the current setup phase so the onboarding wizard only appears when needed.【F:src/main/java/eu/nurkert/neverUp2Late/setup/InitialSetupManager.java†L44-L130】
- `restart-cooldown.json` – Maintains the timestamp of the last automatic restart to enforce the cooldown.【F:src/main/java/eu/nurkert/neverUp2Late/handlers/InstallationHandler.java†L96-L159】

## Troubleshooting Tips

- Use the console log at `FINE` level to inspect fetcher creation and lifecycle decisions; NeverUp2Late logs misconfigured sources
  and disabled lifecycle management prominently.【F:src/main/java/eu/nurkert/neverUp2Late/NeverUp2Late.java†L44-L73】【F:src/main/java/eu/nurkert/neverUp2Late/update/UpdateSourceRegistry.java†L104-L160】
- If quick install cannot determine the correct asset, rerun `/nu2l select <number>` or configure `assetPattern` /
  `archiveEntryPattern` manually in `config.yml` to persist the desired file match.【F:src/main/java/eu/nurkert/neverUp2Late/command/QuickInstallCoordinator.java†L229-L360】
- Lifecycle automation can be disabled entirely by setting `pluginLifecycle.autoManage: false` when you prefer manual restarts.

## Contributing

Pull requests and issue reports are welcome! Please include:

- A clear description of the change or bug.
- Steps to reproduce, including relevant URLs or configuration snippets.
- Logs captured with `debug: true` or `FINE` logging enabled if the issue concerns fetchers or lifecycle actions.

NeverUp2Late is released under the MIT License. Contributions should follow the established code style and avoid wrapping imports
in try/catch blocks, matching the existing codebase conventions.
