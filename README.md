# NeverUp2Late

NeverUp2Late is a Minecraft plugin designed to automatically check for updates and download the latest versions of specified jars (Paper + Geyser). This ensures that your server is always up-to-date with the latest features and security patches.

## Installation

1. Download the latest release of NeverUp2Late from the [releases page](https://github.com/nurkert/NeverUp2Late/releases).
2. Place the `NeverUp2Late.jar` file in your server's `plugins` directory.
3. Start your server to generate the default configuration files.
4. Configure the plugin as needed in the `config.yml` file located in the `plugins/NeverUp2Late` directory.

## Configuration

The `config.yml` file allows you to configure the update interval and the filenames of the plugins to be updated. Below is an example configuration:

```yaml
# The names of the jars as they are in the server directory
filenames:
  geyser: "[PP] Geyser (MODRINTH).jar"
  paper: "paper.jar"
# Check interval in minutes
updateInterval: 30
# Ignore unstable builds
ignoreUnstable: true
```