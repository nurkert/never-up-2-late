![NeverUp2Late banner](https://raw.githubusercontent.com/nurkert/never-up-2-late/master/images/banner.png)

# NeverUp2Late

NeverUp2Late hält deinen Minecraft-Server automatisch auf dem aktuellen Stand. Das Plugin prüft in frei wählbaren Intervallen
Paper, Geyser und beliebige weitere Quellen auf Updates, lädt neue Builds herunter und ersetzt vorhandene JARs. Über das
/nu2l-Kommando lassen sich neue Quellen sekundenschnell direkt aus einer URL einrichten.

## Funktionsumfang

- **Automatische Update-Pipeline** – Fetch, Download und Installation laufen asynchron, inklusive Netzwerk-Wiederholungen und
  Statusmeldungen im Chat.
- **Flexible Quellenverwaltung** – Unterstützt Paper, Geyser, Hangar, Modrinth, CurseForge, GitHub Releases, Jenkins und
  benutzerdefinierte Fetcher-Klassen.
- **Schnellinstallation via `/nu2l`** – URLs aus Hangar, Modrinth, GitHub oder Jenkins werden erkannt, nötige Optionen automatisch
  ausgefüllt und auf Wunsch sofort installiert.
- **Mehrfach-Asset-Auswahl** – Findet ein Release mehrere passende Dateien, generiert das Plugin eine Regex und lässt dich das
  gewünschte Asset direkt auswählen.
- **Versionserkennung** – Optional liest NeverUp2Late die installierte Plugin-Version aus, um nur echte Updates einzuspielen.

## Installation

1. Lade die aktuelle Version von der [SpigotMC-Release-Seite](https://www.spigotmc.org/resources/neverup2late-automatically-keeps-paper-geyser-up-to-date.120768/history).
2. Lege `NeverUp2Late.jar` im `plugins`-Ordner deines Servers ab.
3. Starte den Server einmal, damit Konfigurations- und Persistenzdateien erzeugt werden.
4. Passe die Einstellungen in `plugins/NeverUp2Late/config.yml` an.

## Quick Install (`/nu2l`)

Das Kommando `/nu2l <url>` richtet neue Update-Quellen ohne manuellen YAML-Edit ein. Unterstützt werden derzeit URLs von:

- [Hangar](https://hangar.papermc.io/) Projekten (`https://hangar.papermc.io/<Owner>/<Projekt>`)
- [Modrinth](https://modrinth.com/) Projekt- und Pluginseiten
- GitHub-Repositories (Release-Übersicht oder Direktlinks wie `https://github.com/<Owner>/<Repo>/releases/...`)
- Jenkins-Builds mit `/job/<Name>` in der URL

Ablauf:

1. Kommando ausführen – die Quelle wird analysiert und mit einem sprechenden Namen versehen.
2. Bei mehreren Assets listet der Chat die Kandidaten und du wählst mit `/nu2l select <nummer>` das gewünschte File aus. Die
   daraus erzeugte Regex wird automatisch in die Konfiguration geschrieben.
3. NeverUp2Late speichert die Quelle in `config.yml` und stößt sofort einen Installationslauf an.

> **Berechtigung:** Das Kommando erfordert `neverup2late.install` (Standard: OP). Für die Konsole gelten dieselben Abläufe.

## Konfiguration

Die Datei `plugins/NeverUp2Late/config.yml` enthält globale Einstellungen und die Liste der Quellen.

### Globale Optionen

| Schlüssel                | Bedeutung                                                                 |
|--------------------------|----------------------------------------------------------------------------|
| `filenames.<name>`       | Standarddateiname einer Quelle (z. B. `paper.jar`).                        |
| `updateInterval`         | Prüfintervall in Minuten (Standard: 30).                                   |
| `pluginLifecycle.autoManage` | Aktiviert automatisches Neu-/Entladen aktualisierter Plugins (Standard: aus). |
| `ignoreUnstable`         | Legacy-Schalter, wird noch als Fallback für alte Konfigurationen gelesen. |
| `updates.ignoreUnstable` | Globale Standardeinstellung für Fetcher, die instabile Builds filtern.    |

### Standardkonfiguration

```yaml
filenames:
  geyser: "Geyser-Spigot.jar"
  paper: "paper.jar"

updateInterval: 30
pluginLifecycle:
  autoManage: false
ignoreUnstable: true

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

Jede Quelle besteht aus `name`, `type`, Zielverzeichnis (`server` oder `plugins`), optionalem `filename` und einer `options`
Sektion für fetcher-spezifische Einstellungen. Eigene Fetcher können über den vollqualifizierten Klassennamen registriert werden.

### Unterstützte Fetcher

#### Paper (`type: paper`)
- Nutzt die PaperMC-API und richtet sich automatisch nach der aktuell installierten Minecraft-Version.
- `options.ignoreUnstable` (oder `allowUnstable`) überschreibt den globalen Standard für instabile Builds.

#### Geyser (`type: geyser`)
- Vorkonfigurierte Modrinth-Anbindung mit passenden Loadern und verpflichtender Buildnummer.

#### Hangar (`type: hangar`)
- Benötigt `options.owner` + `options.slug` oder `options.project: Owner/Projekt`.
- Weitere Optionen: `platform` (Standard `PAPER`), `allowedChannels`, `ignoreUnstable`, `allowUnstable`, `requireReviewed`
  (Standard `true`), `preferPinned` (`true`), `pageSize`, `maxPages`, `installedPlugin` zur Versionserkennung.

#### Modrinth (`type: modrinth`)
- Pflichtfeld `options.project` (Slug). Optionale Filter: `loaders`, `statuses` (Standard nur `listed`), `versionTypes`,
  `gameVersions`, `preferPrimaryFile` (Standard `true`), `requireBuildNumber`, `installedPlugin` für Versionsvergleich.

#### CurseForge (`type: curseforge`)
- Pflichtfeld `options.modId`.
- Empfohlene Ergänzungen: `apiKey` (höhere Rate Limits), `pageSize`, `maxPages`, `gameVersions`, `gameVersionTypeIds`,
  `releaseTypes` (`release`, `beta`, `alpha`), `installedPlugin` zur Versionserkennung.

#### GitHub Releases (`type: githubRelease`)
- Benötigt `options.owner` und `options.repository`.
- Optional: `assetPattern` (Regex für Dateiname/URL), `allowPrerelease`, `installedPlugin`.
- Bei mehreren Assets fordert dich NeverUp2Late zur Auswahl auf und speichert das Ergebnis dauerhaft.

#### Jenkins (`type: jenkins`)
- Benötigt `options.baseUrl` (inkl. Protokoll) und `options.job` (Pfad hinter `/job/`).
- Asset-Auswahl via `artifact` oder `artifactPattern`. Weitere Optionen: `preferLastSuccessful` (`true`), `versionSource`
  (`displayName`, `build_id`, `build_number`, `artifact_file`), `versionPattern` (Regex für Versionsauswertung), `installedPlugin`.
  Wenn weder `artifact` noch `artifactPattern` gesetzt ist, muss der Build exakt ein Artefakt liefern.

#### Eigene Fetcher
- Gib als `type` den vollqualifizierten Klassennamen einer `UpdateFetcher`-Implementierung an. Alle angegebenen `options` werden
  unverändert an den Konstruktor weitergereicht.

## Update-Ablauf

NeverUp2Late prüft alle Quellen in dem eingestellten Intervall und nutzt dabei einen dreistufigen Pipeline-Job (Fetch → Download
→ Installation). Fehler werden geloggt; Netzwerkprobleme führen zu einer Warnung und wiederholten Versuchen. Manuell gestartete
Läufe – etwa nach einer Schnellinstallation – melden Erfolg, Fehler und den Ablagepfad direkt im Chat. Nach erfolgreicher
Installation sollte der Server neu gestartet werden, um neue JARs zu laden. Das automatische Neu-/Entladen aktualisierter Plugins ist standardmäßig deaktiviert und kann bei Bedarf über `pluginLifecycle.autoManage` eingeschaltet werden.
