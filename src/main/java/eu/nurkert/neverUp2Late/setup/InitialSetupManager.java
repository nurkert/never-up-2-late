package eu.nurkert.neverUp2Late.setup;

import eu.nurkert.neverUp2Late.Permissions;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt;
import eu.nurkert.neverUp2Late.gui.anvil.AnvilTextPrompt.Prompt;
import eu.nurkert.neverUp2Late.handlers.UpdateHandler;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.persistence.SetupStateRepository;
import eu.nurkert.neverUp2Late.persistence.SetupStateRepository.SetupPhase;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates the interactive initial setup wizard that is shown to operators when the plugin is installed
 * for the first time.
 */
public class InitialSetupManager implements Listener {

    private static final int INVENTORY_SIZE = 27;
    private static final int INFO_SLOT = 4;
    private static final int CONTINUE_SLOT = 22;
    private static final int DOWNLOAD_SLOT = 11;
    private static final int DOWNLOAD_CONTINUE_SLOT = 15;
    private static final int RESTART_NOW_SLOT = 11;
    private static final int RESTART_LATER_SLOT = 15;

    private final PluginContext context;
    private final SetupStateRepository setupStateRepository;
    private final AnvilTextPrompt anvilTextPrompt;
    private final Plugin plugin;
    private final PluginUpdateSettingsRepository updateSettingsRepository;
    private final PluginLifecycleManager pluginLifecycleManager;
    private final UpdateHandler updateHandler;
    private final Server server;
    private final Logger logger;

    private final Map<UUID, SetupSession> sessions = new ConcurrentHashMap<>();

    private volatile boolean setupMode;

    public InitialSetupManager(PluginContext context,
                               SetupStateRepository setupStateRepository,
                               AnvilTextPrompt anvilTextPrompt) {
        this.context = Objects.requireNonNull(context, "context");
        this.setupStateRepository = Objects.requireNonNull(setupStateRepository, "setupStateRepository");
        this.anvilTextPrompt = Objects.requireNonNull(anvilTextPrompt, "anvilTextPrompt");
        this.plugin = context.getPlugin();
        this.updateSettingsRepository = context.getPluginUpdateSettingsRepository();
        this.pluginLifecycleManager = context.getPluginLifecycleManager();
        this.updateHandler = context.getUpdateHandler();
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
    }

    public boolean isSetupMode() {
        return setupMode;
    }

    public void enableSetupMode() {
        this.setupMode = true;
    }

    public void disableSetupMode() {
        this.setupMode = false;
    }

    public void openWizard(Player player) {
        Objects.requireNonNull(player, "player");
        if (!player.hasPermission(Permissions.SETUP)) {
            player.sendMessage(ChatColor.RED + "Du hast keine Berechtigung, um den Einrichtungsassistenten zu öffnen.");
            return;
        }

        SetupPhase phase = setupStateRepository.getPhase();
        if (phase == SetupPhase.COMPLETED) {
            player.sendMessage(ChatColor.GREEN + "NeverUp2Late wurde bereits initialisiert. Du kannst die GUI über /nu2l gui öffnen.");
            return;
        }

        Stage stage = determineStage(phase);
        List<SourceConfiguration> sources = loadSourceConfigurations();
        SetupSession session = new SetupSession(stage, sources);
        sessions.put(player.getUniqueId(), session);
        rebuildInventory(player, session);
    }

    public void completeSetup(CommandSender sender) {
        List<SourceConfiguration> sources = createDefaultSourceConfigurations();
        if (sources.isEmpty()) {
            sendMessage(sender, ChatColor.RED + "Es wurden keine Update-Quellen gefunden. Setup kann nicht abgeschlossen werden.");
            return;
        }
        SetupPhase phase = setupStateRepository.getPhase();
        if (phase == SetupPhase.COMPLETED) {
            sendMessage(sender, ChatColor.YELLOW + "NeverUp2Late wurde bereits eingerichtet – Standardquellen werden erneut gespeichert.");
        }
        finaliseSetup(sender, sources, ChatColor.GREEN + "Initiale Einrichtung abgeschlossen. Standardquellen wurden gespeichert.");
    }

    public void applyConfiguration(CommandSender sender, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            sendMessage(sender, ChatColor.RED + "Bitte gib einen Konfigurationsnamen oder Pfad an.");
            return;
        }

        Optional<File> resolved = resolveConfigurationFile(identifier.trim());
        if (resolved.isEmpty() || !resolved.get().isFile()) {
            sendMessage(sender, ChatColor.RED + "Konfiguration " + ChatColor.AQUA + identifier + ChatColor.RED + " wurde nicht gefunden.");
            return;
        }

        File file = resolved.get();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> configuredSources = yaml.getMapList("updates.sources");
        if (configuredSources == null || configuredSources.isEmpty()) {
            sendMessage(sender, ChatColor.RED + "Die Datei " + ChatColor.AQUA + file.getName() + ChatColor.RED + " enthält keine Update-Quellen.");
            return;
        }

        List<SourceConfiguration> sources = new ArrayList<>();
        for (Map<?, ?> entry : configuredSources) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> option : entry.entrySet()) {
                if (option.getKey() != null) {
                    normalized.put(option.getKey().toString(), option.getValue());
                }
            }
            SourceConfiguration configuration = toSourceConfiguration(normalized);
            if (configuration != null) {
                sources.add(configuration);
            }
        }

        if (sources.isEmpty()) {
            sendMessage(sender, ChatColor.RED + "Keine gültigen Quellen in " + ChatColor.AQUA + file.getName() + ChatColor.RED + " gefunden.");
            return;
        }

        finaliseSetup(sender, sources, ChatColor.GREEN + "Konfiguration aus " + ChatColor.AQUA + file.getName()
                + ChatColor.GREEN + " angewendet. Setup abgeschlossen.");
    }

    public void finishSetup(Player player, boolean restartImmediately) {
        setupStateRepository.setPhase(SetupPhase.COMPLETED);
        disableSetupMode();
        sessions.remove(player.getUniqueId());

        plugin.getServer().getScheduler().runTask(plugin, () -> updateHandler.start());

        player.sendMessage(ChatColor.GREEN + "Initiale Einrichtung abgeschlossen! Updates werden nun automatisch verwaltet.");
        if (restartImmediately) {
            player.sendMessage(ChatColor.YELLOW + "Server wird neu gestartet…");
            plugin.getServer().getScheduler().runTask(plugin, server::shutdown);
        } else {
            player.sendMessage(ChatColor.GRAY + "Du kannst den Server jederzeit neu starten, wenn es dir passt.");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!setupMode) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(Permissions.SETUP)) {
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.sendMessage(ChatColor.AQUA + "Willkommen! NeverUp2Late braucht eine kurze Ersteinrichtung.");
            player.sendMessage(ChatColor.GRAY + "Ein Assistent erklärt dir alle Schritte – er öffnet sich jetzt.");
            openWizard(player);
        }, 40L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        SetupSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!event.getView().getTopInventory().equals(session.inventory)) {
            return;
        }
        if (event.getRawSlot() >= session.inventory.getSize() || event.getRawSlot() < 0) {
            return;
        }

        event.setCancelled(true);

        switch (session.stage) {
            case CONFIGURE -> handleConfigureClick(player, session, event.getRawSlot(), event.isLeftClick(), event.isRightClick(), event.isShiftClick());
            case DOWNLOAD -> handleDownloadClick(player, session, event.getRawSlot());
            case RESTART -> handleRestartClick(player, session, event.getRawSlot());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        SetupSession session = sessions.get(player.getUniqueId());
        if (session != null && session.inventory != null && session.inventory.equals(event.getInventory())) {
            sessions.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private Stage determineStage(SetupPhase phase) {
        return switch (phase) {
            case COMPLETED -> Stage.RESTART;
            case DOWNLOADS_TRIGGERED -> Stage.RESTART;
            case CONFIGURED -> Stage.DOWNLOAD;
            case UNINITIALISED -> Stage.CONFIGURE;
        };
    }

    private void rebuildInventory(Player player, SetupSession session) {
        switch (session.stage) {
            case CONFIGURE -> buildConfigureInventory(player, session);
            case DOWNLOAD -> buildDownloadInventory(player, session);
            case RESTART -> buildRestartInventory(player, session);
        }
    }

    private void buildConfigureInventory(Player player, SetupSession session) {
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE, ChatColor.DARK_PURPLE + "NU2L Setup – Quellen");
        ItemStack filler = createFiller();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        ItemStack info = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Was wird eingerichtet?");
            meta.setLore(List.of(
                    ChatColor.GRAY + "• Links-Klick: Quelle aktivieren/deaktivieren",
                    ChatColor.GRAY + "• Rechts-Klick: Automatische Updates umschalten",
                    ChatColor.GRAY + "• Shift + Links-Klick: Dateinamen anpassen",
                    ChatColor.GRAY + "",
                    ChatColor.AQUA + "Passe jede Quelle an und bestätige unten."
            ));
            info.setItemMeta(meta);
        }
        inventory.setItem(INFO_SLOT, info);

        session.slotMapping.clear();
        int slotIndex = 9;
        for (SourceConfiguration source : session.sources) {
            if (slotIndex >= INVENTORY_SIZE) {
                break;
            }
            inventory.setItem(slotIndex, createSourceItem(source));
            session.slotMapping.put(slotIndex, source);
            slotIndex++;
        }

        ItemStack continueItem = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta continueMeta = continueItem.getItemMeta();
        if (continueMeta != null) {
            continueMeta.setDisplayName(ChatColor.GREEN + "Speichern & Downloads vorbereiten");
            continueMeta.setLore(List.of(
                    ChatColor.GRAY + "Aktuelle Einstellungen werden gespeichert",
                    ChatColor.GRAY + "und die Update-Quellen neu geladen."
            ));
            continueItem.setItemMeta(continueMeta);
        }
        inventory.setItem(CONTINUE_SLOT, continueItem);

        session.inventory = inventory;
        player.openInventory(inventory);
    }

    private void buildDownloadInventory(Player player, SetupSession session) {
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE, ChatColor.DARK_PURPLE + "NU2L Setup – Downloads");
        ItemStack filler = createFiller();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        ItemStack info = new ItemStack(Material.MAP);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Updates herunterladen");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Klicke, um für alle aktivierten Quellen");
            lore.add(ChatColor.GRAY + "einmalig nach Updates zu suchen.");
            if (session.downloadsTriggered) {
                lore.add(" ");
                lore.add(ChatColor.GREEN + "Downloads wurden bereits gestartet.");
            }
            meta.setLore(lore);
            info.setItemMeta(meta);
        }
        inventory.setItem(INFO_SLOT, info);

        ItemStack downloadButton = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta downloadMeta = downloadButton.getItemMeta();
        if (downloadMeta != null) {
            downloadMeta.setDisplayName(ChatColor.GREEN + "Updates jetzt herunterladen");
            downloadMeta.setLore(List.of(
                    ChatColor.GRAY + "Startet einmalig eine Update-Suche",
                    ChatColor.GRAY + "für alle aktivierten Quellen."
            ));
            downloadButton.setItemMeta(downloadMeta);
        }
        inventory.setItem(DOWNLOAD_SLOT, downloadButton);

        ItemStack continueButton = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta continueMeta = continueButton.getItemMeta();
        if (continueMeta != null) {
            continueMeta.setDisplayName(ChatColor.YELLOW + "Weiter zur Neustart-Frage");
            continueMeta.setLore(List.of(
                    ChatColor.GRAY + "Bestätige, dass die Downloads",
                    ChatColor.GRAY + "abgeschlossen sind."
            ));
            continueButton.setItemMeta(continueMeta);
        }
        inventory.setItem(DOWNLOAD_CONTINUE_SLOT, continueButton);

        session.inventory = inventory;
        player.openInventory(inventory);
    }

    private void buildRestartInventory(Player player, SetupSession session) {
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE, ChatColor.DARK_PURPLE + "NU2L Setup – Neustart");
        ItemStack filler = createFiller();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        ItemStack info = new ItemStack(Material.CLOCK);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Alles erledigt!");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Alle benötigten Dateien wurden vorbereitet.",
                    ChatColor.GRAY + "Möchtest du den Server jetzt neu starten?"
            ));
            info.setItemMeta(meta);
        }
        inventory.setItem(INFO_SLOT, info);

        ItemStack restartNow = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta restartMeta = restartNow.getItemMeta();
        if (restartMeta != null) {
            restartMeta.setDisplayName(ChatColor.GREEN + "Server jetzt neu starten");
            restartMeta.setLore(List.of(
                    ChatColor.GRAY + "Empfohlen, damit alle Aktualisierungen",
                    ChatColor.GRAY + "sofort aktiv werden."
            ));
            restartNow.setItemMeta(restartMeta);
        }
        inventory.setItem(RESTART_NOW_SLOT, restartNow);

        ItemStack restartLater = new ItemStack(Material.IRON_BLOCK);
        ItemMeta laterMeta = restartLater.getItemMeta();
        if (laterMeta != null) {
            laterMeta.setDisplayName(ChatColor.YELLOW + "Später neu starten");
            laterMeta.setLore(List.of(
                    ChatColor.GRAY + "Du kannst den Server später manuell",
                    ChatColor.GRAY + "neu starten. Updates laufen trotzdem."
            ));
            restartLater.setItemMeta(laterMeta);
        }
        inventory.setItem(RESTART_LATER_SLOT, restartLater);

        session.inventory = inventory;
        player.openInventory(inventory);
    }

    private ItemStack createSourceItem(SourceConfiguration source) {
        Material material = source.target == TargetDirectory.SERVER ? Material.COMPASS : Material.BOOK;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            ChatColor statusColor = source.enabled ? ChatColor.GREEN : ChatColor.RED;
            meta.setDisplayName(statusColor + source.displayName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Typ: " + ChatColor.AQUA + source.type);
            lore.add(ChatColor.GRAY + "Ziel: " + ChatColor.AQUA + source.target.name().toLowerCase(Locale.ROOT));
            lore.add(ChatColor.GRAY + "Datei: " + ChatColor.WHITE + source.filename);
            if (source.fileExists) {
                lore.add(ChatColor.GREEN + "✔ Datei gefunden");
            } else {
                lore.add(ChatColor.RED + "✘ Datei nicht gefunden");
                if (source.suggestedFilename != null) {
                    lore.add(ChatColor.GRAY + "Gefunden: " + ChatColor.AQUA + source.suggestedFilename);
                }
            }
            if (source.target == TargetDirectory.PLUGINS) {
                lore.add(ChatColor.GRAY + "Automatische Updates: " + (source.autoUpdate ? ChatColor.GREEN + "Aktiv" : ChatColor.RED + "Aus"));
                if (source.pluginName == null) {
                    lore.add(ChatColor.YELLOW + "Plugin wird erst nach Installation erkannt.");
                }
            }
            lore.add(ChatColor.DARK_GRAY + "");
            lore.add(ChatColor.GRAY + "Links-Klick: Quelle aktivieren/deaktivieren");
            if (source.target == TargetDirectory.PLUGINS) {
                lore.add(ChatColor.GRAY + "Rechts-Klick: Auto-Update umschalten");
            }
            lore.add(ChatColor.GRAY + "Shift + Links-Klick: Dateinamen ändern");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFiller() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }
        return filler;
    }

    private void handleConfigureClick(Player player,
                                       SetupSession session,
                                       int rawSlot,
                                       boolean leftClick,
                                       boolean rightClick,
                                       boolean shiftClick) {
        SourceConfiguration source = session.slotMapping.get(rawSlot);
        if (source != null) {
            if (shiftClick && leftClick) {
                promptFilename(player, session, source, rawSlot);
                return;
            }
            if (leftClick) {
                source.enabled = !source.enabled;
                player.sendMessage(ChatColor.GRAY + "Quelle " + ChatColor.AQUA + source.displayName() + ChatColor.GRAY
                        + " ist jetzt " + (source.enabled ? ChatColor.GREEN + "aktiv" : ChatColor.RED + "deaktiviert"));
                session.inventory.setItem(rawSlot, createSourceItem(source));
                return;
            }
            if (rightClick && source.target == TargetDirectory.PLUGINS) {
                source.autoUpdate = !source.autoUpdate;
                player.sendMessage(ChatColor.GRAY + "Automatische Updates für " + ChatColor.AQUA
                        + source.displayName() + ChatColor.GRAY + ": "
                        + (source.autoUpdate ? ChatColor.GREEN + "aktiv" : ChatColor.RED + "aus"));
                session.inventory.setItem(rawSlot, createSourceItem(source));
                return;
            }
            return;
        }

        if (rawSlot == CONTINUE_SLOT) {
            persistConfiguration(player, session.sources);
            setupStateRepository.setPhase(SetupPhase.CONFIGURED);
            session.stage = Stage.DOWNLOAD;
            rebuildInventory(player, session);
        }
    }

    private void handleDownloadClick(Player player, SetupSession session, int rawSlot) {
        if (rawSlot == DOWNLOAD_SLOT) {
            triggerDownloads(player, session);
        } else if (rawSlot == DOWNLOAD_CONTINUE_SLOT) {
            setupStateRepository.setPhase(SetupPhase.DOWNLOADS_TRIGGERED);
            session.stage = Stage.RESTART;
            rebuildInventory(player, session);
        }
    }

    private void handleRestartClick(Player player, SetupSession session, int rawSlot) {
        if (rawSlot == RESTART_NOW_SLOT) {
            player.closeInventory();
            finishSetup(player, true);
        } else if (rawSlot == RESTART_LATER_SLOT) {
            player.closeInventory();
            finishSetup(player, false);
        }
    }

    private void triggerDownloads(Player player, SetupSession session) {
        session.downloadsTriggered = true;
        for (SourceConfiguration source : session.sources) {
            if (!source.enabled) {
                continue;
            }
            Optional<UpdateSource> updateSource = context.getUpdateSourceRegistry().findSource(source.name);
            if (updateSource.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Quelle " + source.displayName()
                        + " konnte nicht gefunden werden. Prüfe die Konfiguration.");
                continue;
            }
            updateHandler.runJobNow(updateSource.get(), player);
        }
        player.sendMessage(ChatColor.GREEN + "Downloads wurden gestartet. Du bekommst im Chat eine Rückmeldung.");
        buildDownloadInventory(player, session);
    }

    private void persistConfiguration(CommandSender sender, List<SourceConfiguration> sources) {
        for (SourceConfiguration source : sources) {
            context.getConfiguration().set("filenames." + source.name, source.filename);
        }

        List<Map<String, Object>> serializedSources = new ArrayList<>();
        for (SourceConfiguration source : sources) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", source.name);
            data.put("type", source.type);
            data.put("target", source.targetConfigValue);
            data.put("enabled", source.enabled);
            if (source.filename != null && !source.filename.isBlank()) {
                data.put("filename", source.filename);
            }
            if (!source.options.isEmpty()) {
                data.put("options", new LinkedHashMap<>(source.options));
            }
            serializedSources.add(data);
        }
        context.getConfiguration().set("updates.sources", serializedSources);
        plugin.saveConfig();

        if (pluginLifecycleManager != null && updateSettingsRepository != null) {
            for (SourceConfiguration source : sources) {
                if (source.target != TargetDirectory.PLUGINS) {
                    continue;
                }
                if (source.pluginName == null || source.pluginName.isBlank()) {
                    continue;
                }
                PluginUpdateSettings current = updateSettingsRepository.getSettings(source.pluginName);
                PluginUpdateSettings updated = new PluginUpdateSettings(
                        source.autoUpdate,
                        current.behaviour(),
                        current.retainUpstreamFilename()
                );
                updateSettingsRepository.saveSettings(source.pluginName, updated);
            }
        }

        try {
            context.getUpdateSourceRegistry().reload();
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Konnte Update-Quellen nach der Einrichtung nicht neu laden", ex);
        }

        sendMessage(sender, ChatColor.GREEN + "Einstellungen gespeichert. Die Quellen wurden aktualisiert.");
    }

    private void promptFilename(Player player, SetupSession session, SourceConfiguration source, int slot) {
        Prompt prompt = AnvilTextPrompt.Prompt.builder()
                .title(ChatColor.DARK_PURPLE + "Dateinamen anpassen")
                .initialText(source.filename)
                .validation(value -> {
                    if (value == null || value.isBlank()) {
                        return Optional.of(ChatColor.RED + "Bitte gib einen Dateinamen an.");
                    }
                    if (!value.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        return Optional.of(ChatColor.RED + "Der Dateiname muss auf .jar enden.");
                    }
                    return Optional.empty();
                })
                .onConfirm((player1, value) -> {
                    source.filename = value.trim();
                    updateSourceDetection(source);
                    session.inventory.setItem(slot, createSourceItem(source));
                    player1.sendMessage(ChatColor.GREEN + "Dateiname für " + ChatColor.AQUA
                            + source.displayName() + ChatColor.GREEN + " aktualisiert.");
                })
                .onCancel(p -> sessions.computeIfPresent(p.getUniqueId(), (uuid, s) -> {
                    updateSourceDetection(source);
                    session.inventory.setItem(slot, createSourceItem(source));
                    return s;
                }))
                .build();
        anvilTextPrompt.open(player, prompt);
    }

    private void updateSourceDetection(SourceConfiguration source) {
        File base = source.target == TargetDirectory.SERVER
                ? server.getWorldContainer().getAbsoluteFile()
                : plugin.getDataFolder().getParentFile();
        File file = new File(base, source.filename);
        source.fileExists = file.exists();
        source.suggestedFilename = source.fileExists ? null : findCandidateFilename(source);
        if (source.target == TargetDirectory.PLUGINS && pluginLifecycleManager != null) {
            Path path = file.toPath().toAbsolutePath();
            Optional<ManagedPlugin> managed = pluginLifecycleManager.findByPath(path);
            managed.ifPresentOrElse(
                    plugin -> source.pluginName = plugin.getName(),
                    () -> {
                        if (source.options.containsKey("installedPlugin")) {
                            Object value = source.options.get("installedPlugin");
                            source.pluginName = value != null ? value.toString() : null;
                        } else {
                            source.pluginName = null;
                        }
                    }
            );
        }
    }

    private String findCandidateFilename(SourceConfiguration source) {
        File directory = source.target == TargetDirectory.SERVER
                ? server.getWorldContainer().getAbsoluteFile()
                : plugin.getDataFolder().getParentFile();
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) {
            return null;
        }
        String needle = source.name.toLowerCase(Locale.ROOT);
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.contains(needle)) {
                return file.getName();
            }
        }
        return null;
    }

    private List<SourceConfiguration> loadSourceConfigurations() {
        List<Map<String, Object>> rawSources = readConfiguredSources();
        if (rawSources.isEmpty()) {
            return createDefaultSourceConfigurations();
        }

        List<SourceConfiguration> result = new ArrayList<>();
        for (Map<String, Object> entry : rawSources) {
            SourceConfiguration configuration = toSourceConfiguration(entry);
            if (configuration != null) {
                result.add(configuration);
            }
        }
        return result;
    }

    private List<Map<String, Object>> readConfiguredSources() {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<?, ?>> configuredSources = context.getConfiguration().getMapList("updates.sources");
        if (configuredSources != null && !configuredSources.isEmpty()) {
            for (Map<?, ?> raw : configuredSources) {
                if (raw == null) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                for (Map.Entry<?, ?> value : raw.entrySet()) {
                    if (value.getKey() != null) {
                        entry.put(value.getKey().toString(), value.getValue());
                    }
                }
                entries.add(entry);
            }
        }

        if (!entries.isEmpty()) {
            return entries;
        }

        var section = context.getConfiguration().getConfigurationSection("updates.sources");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                var sourceSection = section.getConfigurationSection(key);
                if (sourceSection == null) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", sourceSection.getString("name", key));
                entry.put("type", sourceSection.getString("type"));
                entry.put("target", sourceSection.getString("target"));
                entry.put("filename", sourceSection.getString("filename"));
                entry.put("enabled", sourceSection.getBoolean("enabled", true));
                var optionsSection = sourceSection.getConfigurationSection("options");
                if (optionsSection != null) {
                    entry.put("options", new LinkedHashMap<>(optionsSection.getValues(true)));
                }
                entries.add(entry);
            }
        }
        return entries;
    }

    private SourceConfiguration toSourceConfiguration(Map<String, Object> entry) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        String name = asString(entry.get("name"));
        String type = asString(entry.get("type"));
        if (name == null || name.isBlank() || type == null || type.isBlank()) {
            return null;
        }
        String targetValue = asString(entry.get("target"));
        TargetDirectory target = parseTargetDirectory(targetValue);
        String filename = asString(entry.get("filename"));
        if (filename == null || filename.isBlank()) {
            filename = context.getConfiguration().getString("filenames." + name);
        }
        if (filename == null || filename.isBlank()) {
            filename = name + ".jar";
        }

        boolean enabled = parseBoolean(entry.get("enabled"), true);
        Map<String, Object> options = toOptions(entry.get("options"));
        SourceConfiguration configuration = new SourceConfiguration();
        configuration.name = name;
        configuration.type = type;
        configuration.target = target;
        configuration.targetConfigValue = targetValue != null ? targetValue : target.name();
        configuration.filename = filename;
        configuration.enabled = enabled;
        configuration.options = options;
        configuration.pluginName = asString(options.get("installedPlugin"));
        configuration.autoUpdate = true;
        updateSourceDetection(configuration);
        if (configuration.pluginName != null && updateSettingsRepository != null) {
            PluginUpdateSettings settings = updateSettingsRepository.getSettings(configuration.pluginName);
            configuration.autoUpdate = settings.autoUpdateEnabled();
        }
        return configuration;
    }

    private TargetDirectory parseTargetDirectory(String targetValue) {
        if (targetValue == null || targetValue.isBlank()) {
            return TargetDirectory.PLUGINS;
        }
        try {
            return TargetDirectory.valueOf(targetValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.log(Level.WARNING, "Unbekanntes Ziel {0} für Update-Quelle – verwende plugins.", targetValue);
            return TargetDirectory.PLUGINS;
        }
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private Map<String, Object> toOptions(Object options) {
        if (!(options instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> option : map.entrySet()) {
            if (option.getKey() != null) {
                result.put(option.getKey().toString(), option.getValue());
            }
        }
        return result;
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private void finaliseSetup(CommandSender sender, List<SourceConfiguration> sources, String completionMessage) {
        persistConfiguration(sender, sources);
        setupStateRepository.setPhase(SetupPhase.COMPLETED);
        disableSetupMode();
        sessions.clear();
        plugin.getServer().getScheduler().runTask(plugin, () -> updateHandler.start());
        sendMessage(sender, completionMessage);
        if (sender != null) {
            logger.info(ChatColor.stripColor(completionMessage));
        }
    }

    private List<SourceConfiguration> createDefaultSourceConfigurations() {
        List<SourceConfiguration> fallback = new ArrayList<>();
        for (UpdateSource source : context.getUpdateSourceRegistry().getSources()) {
            SourceConfiguration configuration = new SourceConfiguration();
            configuration.name = source.getName();
            configuration.type = source.getFetcher().getClass().getSimpleName();
            configuration.target = source.getTargetDirectory();
            configuration.targetConfigValue = source.getTargetDirectory().name();
            configuration.filename = source.getFilename();
            configuration.enabled = true;
            configuration.options = Collections.emptyMap();
            configuration.autoUpdate = true;
            configuration.pluginName = source.getInstalledPluginName();
            updateSourceDetection(configuration);
            fallback.add(configuration);
        }
        return fallback;
    }

    private Optional<File> resolveConfigurationFile(String identifier) {
        File file = new File(identifier);
        if (file.isFile()) {
            return Optional.of(file);
        }
        if (file.isAbsolute()) {
            return Optional.empty();
        }
        File dataFolder = plugin.getDataFolder();
        File direct = new File(dataFolder, identifier);
        if (direct.isFile()) {
            return Optional.of(direct);
        }
        if (!identifier.endsWith(".yml")) {
            File withExtension = new File(dataFolder, identifier + ".yml");
            if (withExtension.isFile()) {
                return Optional.of(withExtension);
            }
        }
        File presetsDir = new File(dataFolder, "setup-presets");
        File preset = new File(presetsDir, identifier);
        if (preset.isFile()) {
            return Optional.of(preset);
        }
        if (!identifier.endsWith(".yml")) {
            File presetWithExtension = new File(presetsDir, identifier + ".yml");
            if (presetWithExtension.isFile()) {
                return Optional.of(presetWithExtension);
            }
        }
        return Optional.of(file).filter(File::isFile);
    }

    private void sendMessage(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(message);
        } else {
            logger.info(ChatColor.stripColor(message));
        }
    }

    private enum Stage {
        CONFIGURE,
        DOWNLOAD,
        RESTART
    }

    private static final class SetupSession {
        private Stage stage;
        private final List<SourceConfiguration> sources;
        private final Map<Integer, SourceConfiguration> slotMapping = new HashMap<>();
        private Inventory inventory;
        private boolean downloadsTriggered;

        private SetupSession(Stage stage, List<SourceConfiguration> sources) {
            this.stage = stage;
            this.sources = sources;
        }
    }

    private static final class SourceConfiguration {
        private String name;
        private String type;
        private TargetDirectory target;
        private String targetConfigValue;
        private String filename;
        private boolean enabled;
        private boolean autoUpdate = true;
        private boolean fileExists;
        private String suggestedFilename;
        private Map<String, Object> options = new LinkedHashMap<>();
        private String pluginName;

        private String displayName() {
            return name != null ? name : "Quelle";
        }
    }
}
