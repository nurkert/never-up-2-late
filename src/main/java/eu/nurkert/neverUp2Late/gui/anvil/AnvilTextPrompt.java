package eu.nurkert.neverUp2Late.gui.anvil;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Provides a reusable anvil based text input prompt that can be used by different GUI flows.
 */
public class AnvilTextPrompt implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Prompts running as a chat question because no anvil view was available. */
    private final Map<UUID, ChatPrompt> chatPrompts = new ConcurrentHashMap<>();

    /** A chat question expires, so a later unrelated message is never mistaken for the answer. */
    private static final long CHAT_PROMPT_TIMEOUT_TICKS = 20L * 60L;

    public AnvilTextPrompt(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Opens a new anvil prompt for the supplied player. If another prompt is already active for the player it
     * will be cancelled first.
     */
    public void open(Player player, Prompt prompt) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(prompt, "prompt");

        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> open(player, prompt));
            return;
        }

        Session existing = sessions.remove(player.getUniqueId());
        if (existing != null) {
            existing.cancel(player);
        }
        cancelChatPrompt(player, false);

        Inventory inventory = null;
        try {
            inventory = Bukkit.createInventory(null, InventoryType.ANVIL, prompt.title());
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "Server refused to create an anvil view", ex);
        }
        if (!(inventory instanceof AnvilInventory anvilInventory)) {
            // Not every server hands out a usable anvil view through the API.
            // Throwing here happened inside an inventory click handler, which
            // Bukkit reports as a plugin error and which left the operator with
            // a dead screen and no way to enter anything. Ask in chat instead.
            plugin.getLogger().log(java.util.logging.Level.FINE,
                    "No usable anvil view on this server; falling back to chat input.");
            startChatPrompt(player, prompt);
            return;
        }

        ItemStack template = prompt.inputItem().clone();
        ItemMeta meta = template.getItemMeta();
        if (meta != null && prompt.initialText() != null && !prompt.initialText().isBlank()) {
            meta.setDisplayName(prompt.initialText());
            template.setItemMeta(meta);
        }

        anvilInventory.setItem(0, template);
        anvilInventory.setRepairCost(0);

        Session session = new Session(prompt, anvilInventory);
        sessions.put(player.getUniqueId(), session);

        player.openInventory(anvilInventory);
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }

        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.matches(event.getInventory())) {
            return;
        }

        session.inventory().setRepairCost(0);

        String renameText = event.getInventory().getRenameText();
        String value = renameText != null ? renameText.trim() : "";

        Optional<String> validation = session.validate(value);
        if (validation.isPresent()) {
            event.setResult(null);
            return;
        }

        ItemStack preview = session.createPreview(value);
        event.setResult(preview);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.matches(event.getView().getTopInventory())) {
            return;
        }

        if (event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        if (event.getSlotType() == InventoryType.SlotType.RESULT) {
            event.setCancelled(true);
            handleResultClick(player, session);
            return;
        }

        if (event.getClickedInventory() != null && event.getClickedInventory().equals(session.inventory())) {
            event.setCancelled(true);
        }
    }

    private void handleResultClick(Player player, Session session) {
        String renameText = session.inventory().getRenameText();
        String value = renameText != null ? renameText.trim() : "";

        Optional<String> validation = session.validate(value);
        if (validation.isPresent()) {
            validation.ifPresent(player::sendMessage);
            return;
        }

        sessions.remove(player.getUniqueId());
        player.closeInventory();
        session.prompt().onConfirm().accept(player, value);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.matches(event.getInventory())) {
            sessions.remove(player.getUniqueId());
            // Cancel callbacks typically reopen the previous menu. Opening an
            // inventory from inside InventoryCloseEvent leaves the client with a
            // dead screen, because the server is still closing the old one - so
            // hand it to the next tick.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    session.cancel(player);
                }
            });
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        ChatPrompt chatPrompt = chatPrompts.remove(playerId);
        if (chatPrompt != null) {
            chatPrompt.cancelExpiry();
            chatPrompt.prompt().onCancel().accept(event.getPlayer());
        }
        Session session = sessions.remove(playerId);
        if (session != null) {
            session.cancel(event.getPlayer());
        }
    }

    private void startChatPrompt(Player player, Prompt prompt) {
        UUID playerId = player.getUniqueId();
        // Without an expiry the entry lives forever, and the next thing the
        // operator types in chat - minutes later, about something else - would
        // be swallowed as the answer and rename or reload a plugin.
        org.bukkit.scheduler.BukkitTask expiry = plugin.isEnabled()
                ? plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> expireChatPrompt(playerId), CHAT_PROMPT_TIMEOUT_TICKS)
                : null;
        chatPrompts.put(playerId, new ChatPrompt(prompt, expiry));
        player.closeInventory();
        player.sendMessage(ChatColor.AQUA + prompt.title());
        if (prompt.initialText() != null && !prompt.initialText().isBlank()) {
            player.sendMessage(ChatColor.GRAY + "Current value: " + ChatColor.WHITE + prompt.initialText());
        }
        player.sendMessage(ChatColor.GRAY + "Type the new value in chat within 60 seconds, or "
                + ChatColor.WHITE + "cancel" + ChatColor.GRAY + " to abort.");
    }

    private void expireChatPrompt(UUID playerId) {
        ChatPrompt expired = chatPrompts.remove(playerId);
        if (expired == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.GRAY + "Input timed out.");
            expired.prompt().onCancel().accept(player);
        }
    }

    /** Drops a running chat question, optionally telling its cancel callback. */
    private void cancelChatPrompt(Player player, boolean invokeCallback) {
        ChatPrompt running = chatPrompts.remove(player.getUniqueId());
        if (running == null) {
            return;
        }
        running.cancelExpiry();
        if (invokeCallback && player.isOnline()) {
            running.prompt().onCancel().accept(player);
        }
    }

    private record ChatPrompt(Prompt prompt, org.bukkit.scheduler.BukkitTask expiry) {
        void cancelExpiry() {
            if (expiry != null) {
                expiry.cancel();
            }
        }
    }

    /**
     * Reads the answer for a chat fallback prompt. Runs off the server thread,
     * so the callbacks - which open inventories and touch the configuration -
     * are handed back to it.
     */
    @EventHandler
    public void onChatInput(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ChatPrompt active = chatPrompts.remove(player.getUniqueId());
        if (active == null) {
            return;
        }
        active.cancelExpiry();
        Prompt prompt = active.prompt();
        event.setCancelled(true);

        String value = event.getMessage() == null ? "" : event.getMessage().trim();
        if (value.equalsIgnoreCase("cancel")) {
            runOnServerThread(() -> {
                if (player.isOnline()) {
                    prompt.onCancel().accept(player);
                }
            });
            return;
        }

        Optional<String> validation = prompt.validation().apply(value);
        if (validation.isPresent()) {
            player.sendMessage(ChatColor.RED + validation.get());
            player.sendMessage(ChatColor.GRAY + "Please try again, or type "
                    + ChatColor.WHITE + "cancel" + ChatColor.GRAY + ".");
            startChatPrompt(player, prompt);
            return;
        }
        runOnServerThread(() -> {
            if (player.isOnline()) {
                prompt.onConfirm().accept(player, value);
            }
        });
    }

    private void runOnServerThread(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (org.bukkit.plugin.IllegalPluginAccessException | IllegalStateException ex) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "Server is shutting down; dropping prompt result", ex);
        }
    }

    public record Prompt(String title,
                         String initialText,
                         ItemStack inputItem,
                         Function<String, ItemStack> previewFactory,
                         Function<String, Optional<String>> validation,
                         BiConsumer<Player, String> onConfirm,
                         Consumer<Player> onCancel) {

        public static Builder builder() {
            return new Builder();
        }
    }

    public static final class Builder {

        private String title = ChatColor.DARK_PURPLE + "Input";
        private String initialText = "";
        private ItemStack inputItem = new ItemStack(Material.PAPER);
        private Function<String, ItemStack> previewFactory;
        private Function<String, Optional<String>> validation = value -> {
            if (value == null || value.isBlank()) {
                return Optional.of(ChatColor.RED + "Please enter a value.");
            }
            return Optional.empty();
        };
        private BiConsumer<Player, String> onConfirm = (player, value) -> { };
        private Consumer<Player> onCancel = player -> { };

        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        public Builder initialText(String initialText) {
            this.initialText = initialText != null ? initialText : "";
            return this;
        }

        public Builder inputItem(ItemStack inputItem) {
            this.inputItem = inputItem != null ? inputItem.clone() : new ItemStack(Material.PAPER);
            return this;
        }

        public Builder previewFactory(Function<String, ItemStack> previewFactory) {
            this.previewFactory = previewFactory;
            return this;
        }

        public Builder validation(Function<String, Optional<String>> validation) {
            this.validation = validation != null ? validation : this.validation;
            return this;
        }

        public Builder onConfirm(BiConsumer<Player, String> onConfirm) {
            this.onConfirm = onConfirm != null ? onConfirm : this.onConfirm;
            return this;
        }

        public Builder onCancel(Consumer<Player> onCancel) {
            this.onCancel = onCancel != null ? onCancel : this.onCancel;
            return this;
        }

        public Prompt build() {
            ItemStack template = inputItem != null ? inputItem.clone() : new ItemStack(Material.PAPER);
            Function<String, ItemStack> preview = previewFactory != null ? previewFactory : value -> {
                ItemStack result = new ItemStack(template.getType());
                ItemMeta meta = result.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GREEN + value);
                    result.setItemMeta(meta);
                }
                return result;
            };
            return new Prompt(title, initialText, template, preview, validation, onConfirm, onCancel);
        }
    }

    private record Session(Prompt prompt, AnvilInventory inventory) {

        boolean matches(Inventory other) {
            return inventory.equals(other);
        }

        Optional<String> validate(String value) {
            return prompt.validation().apply(value);
        }

        ItemStack createPreview(String value) {
            ItemStack preview = prompt.previewFactory().apply(value);
            if (preview == null) {
                return null;
            }
            ItemStack clone = preview.clone();
            if (!clone.hasItemMeta()) {
                ItemMeta meta = clone.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GREEN + value);
                    clone.setItemMeta(meta);
                }
            }
            return clone;
        }

        void cancel(Player player) {
            prompt.onCancel().accept(player);
        }
    }
}

