package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.core.PluginContext;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.UpdateBehaviour;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.update.suggestion.PluginLinkSuggestion;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Items and lookups more than one screen needs: the plugin status item, suggestion
 * entries, what a managed plugin is linked to and where it stands.
 */
final class GuiItems {

    private static final eu.nurkert.neverUp2Late.update.VersionComparator VERSION_COMPARATOR =
            new eu.nurkert.neverUp2Late.update.VersionComparator();

    private final PluginContext context;
    private final PluginUpdateSettingsRepository updateSettingsRepository;

    GuiItems(PluginContext context, PluginUpdateSettingsRepository updateSettingsRepository) {
        this.context = context;
        this.updateSettingsRepository = updateSettingsRepository;
    }

    /** Where {@code plugin} stands - what decides its filter, its shimmer and the frame colour. */
    PluginStatus statusOf(ManagedPlugin plugin) {
        Optional<UpdateSource> source = findMatchingSource(plugin);
        Optional<UpdateStateRepository.CheckState> checkState = source
                .flatMap(linked -> context.getPersistentPluginHandler().getCheckState(linked.getName()));
        boolean failed = checkState
                .map(state -> state.result() == UpdateStateRepository.CheckResult.FAILED)
                .orElse(false);
        boolean ready = checkState.map(state -> isNewerThanRunning(state, plugin)).orElse(false);
        return PluginStatus.of(source.isPresent(), failed, ready);
    }

    PluginSummary summarize(Collection<ManagedPlugin> plugins) {
        return PluginSummary.of(plugins.stream().map(this::statusOf).toList());
    }

    /**
     * A plugin as one item: what runs, what is waiting, where it comes from
     * and when that was last asked - then, after a gap, what a click does.
     *
     * @param hints the action lines for the screen this item is shown on; may be empty
     */
    ItemStack createPluginItem(ManagedPlugin plugin, List<String> hints) {
        Optional<UpdateSource> source = findMatchingSource(plugin);
        Optional<UpdateStateRepository.CheckState> checkState = source
                .flatMap(linked -> context.getPersistentPluginHandler().getCheckState(linked.getName()));
        boolean updateWaiting = checkState.map(state -> isNewerThanRunning(state, plugin)).orElse(false);
        boolean failed = checkState
                .map(state -> state.result() == UpdateStateRepository.CheckResult.FAILED)
                .orElse(false);
        Material material = updateWaiting
                ? Material.WRITABLE_BOOK
                : source.isPresent() ? Material.ENCHANTED_BOOK : Material.BOOK;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String marker = failed ? Theme.ERROR + Theme.WARN + " "
                    : updateWaiting ? Theme.SUCCESS + Theme.UP + " " : "";
            meta.setDisplayName(marker + Theme.HEADING + plugin.getName());

            List<String> lore = new ArrayList<>();
            String running = plugin.getPlugin()
                    .map(loaded -> loaded.getDescription().getVersion())
                    .filter(version -> !version.isBlank())
                    .orElse(null);
            if (updateWaiting) {
                lore.add(Theme.MUTED + "Version: " + Theme.TEXT + running + Theme.SUBTLE + " " + Theme.ARROW
                        + " " + Theme.SUCCESS + ChatColor.BOLD + checkState.get().latestVersion());
            } else if (running != null) {
                lore.add(Theme.MUTED + "Version: " + Theme.ACCENT + running);
            }
            source.ifPresentOrElse(
                    linked -> lore.add(Theme.MUTED + "Source: " + Theme.ACCENT + linked.getName()),
                    () -> lore.add(Theme.MUTED + "Source: " + Theme.SUBTLE + "not linked"));
            checkState.ifPresent(state -> lore.addAll(describeCheckState(state, updateWaiting)));
            lore.add(Theme.MUTED + "Status: " + statusLabel(plugin));
            Path path = plugin.getPath();
            if (path != null) {
                lore.add(Theme.MUTED + "File: " + Theme.TEXT + path.getFileName());
            } else {
                lore.add(Theme.ERROR + "No jar file found - cannot link.");
            }

            PluginUpdateSettings settings = readSettings(plugin);
            if (!settings.autoUpdateEnabled()) {
                lore.add(Theme.MUTED + "Updates: " + Theme.ERROR + "Automatic updates disabled");
            } else if (settings.behaviour() == UpdateBehaviour.AUTO_RELOAD) {
                lore.add(Theme.MUTED + "Updates: " + Theme.SUCCESS + "Automatically reload");
            } else {
                lore.add(Theme.MUTED + "Updates: " + Theme.HEADING + "Server restart required");
            }
            if (isSelfPlugin(plugin)) {
                lore.add(Theme.SUBTLE + "Managed by NeverUp2Late itself.");
            }
            if (hints != null && !hints.isEmpty()) {
                lore.add(" ");
                lore.addAll(hints);
            }

            meta.setLore(lore);
            GuiChrome.quiet(meta);
            GuiChrome.glint(meta, updateWaiting);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String statusLabel(ManagedPlugin plugin) {
        if (plugin.isEnabled()) {
            return Theme.SUCCESS + "Active";
        }
        if (plugin.isLoaded()) {
            return Theme.WARNING + "Loaded";
        }
        return Theme.ERROR + "Not loaded";
    }

    PluginUpdateSettings readSettings(ManagedPlugin plugin) {
        if (updateSettingsRepository == null) {
            return PluginUpdateSettings.defaultSettings();
        }
        String name = plugin.getName();
        if (name == null || name.isBlank()) {
            return PluginUpdateSettings.defaultSettings();
        }
        return updateSettingsRepository.getSettings(name);
    }

    boolean isSelfPlugin(ManagedPlugin plugin) {
        if (plugin == null) {
            return false;
        }
        Plugin self = context.getPlugin();
        if (self == null) {
            return false;
        }
        if (plugin.getPlugin().map(self::equals).orElse(false)) {
            return true;
        }
        String pluginName = plugin.getName();
        if (pluginName != null && pluginName.equalsIgnoreCase(self.getName())) {
            return true;
        }
        Path managedPath = plugin.getPath();
        if (managedPath == null) {
            return false;
        }
        try {
            CodeSource codeSource = self.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return false;
            }
            Path selfPath = Path.of(codeSource.getLocation().toURI());
            if (!Files.exists(selfPath)) {
                return false;
            }
            Path normalizedManaged = managedPath.toAbsolutePath().normalize();
            Path normalizedSelf = selfPath.toAbsolutePath().normalize();
            return normalizedManaged.equals(normalizedSelf);
        } catch (Exception ex) {
            return false;
        }
    }

    ItemStack createSuggestionItem(PluginLinkSuggestion suggestion) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String title = Objects.requireNonNullElse(suggestion.title(), suggestion.provider());
            meta.setDisplayName(Theme.ACCENT + title + Theme.SUBTLE + " (" + suggestion.provider() + ")");
            List<String> lore = new ArrayList<>();
            lore.add(Theme.MUTED + suggestion.provider() + " suggestion");
            for (String line : wrapText(suggestion.description(), 40)) {
                lore.add(Theme.SUBTLE + line);
            }
            if (!suggestion.highlights().isEmpty()) {
                lore.add(" ");
                for (String highlight : suggestion.highlights()) {
                    lore.add(Theme.MUTED + Theme.DOT + " " + Theme.WARNING + highlight);
                }
            }
            lore.add(" ");
            lore.add(Theme.SUBTLE + suggestion.url());
            lore.add(" ");
            lore.add(GuiChrome.hints("Click: use this link"));
            meta.setLore(lore);
            GuiChrome.quiet(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> wrapText(String text, int maxLength) {
        if (text == null) {
            return List.of();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || maxLength <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : trimmed.split("\\s+")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > maxLength) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    String stripJarExtension(String value) {
        if (value == null) {
            return "";
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return value.substring(0, value.length() - 4);
        }
        return value;
    }

    /**
     * The two lines an operator opens this screen for: is there something newer,
     * and did the last look actually work. Everything else on the item describes
     * what is installed or which policy applies - neither answers the question.
     */
    private List<String> describeCheckState(UpdateStateRepository.CheckState state, boolean updateWaiting) {
        List<String> lines = new ArrayList<>();
        lines.add(Theme.MUTED + "Last check: " + Theme.TEXT + describeAge(state.checkedAt()));
        if (state.result() == UpdateStateRepository.CheckResult.FAILED) {
            String reason = state.error() == null || state.error().isBlank() ? "unknown reason" : state.error();
            lines.add(Theme.ERROR + Theme.CROSS + " Failed: " + Theme.TEXT + reason);
        } else if (updateWaiting) {
            lines.add(Theme.SUCCESS + Theme.UP + " Update ready " + Theme.MUTED + "- applied on the next restart.");
        } else {
            lines.add(Theme.SUCCESS + Theme.CHECK + " Up to date");
        }
        return lines;
    }

    /**
     * Whether the version already written to disk differs from the one the
     * running plugin reports - i.e. a jar is waiting for a restart.
     */
    private boolean isNewerThanRunning(UpdateStateRepository.CheckState state, ManagedPlugin plugin) {
        String available = state.latestVersion();
        if (available == null || available.isBlank()) {
            return false;
        }
        String running = plugin.getPlugin()
                .map(loaded -> loaded.getDescription().getVersion())
                .orElse(null);
        if (running == null || running.isBlank()) {
            return false;
        }
        return VERSION_COMPARATOR.compare(running, available) < 0;
    }

    private String describeAge(long epochMillis) {
        if (epochMillis <= 0L) {
            return "never";
        }
        long minutes = Math.max(0L, (System.currentTimeMillis() - epochMillis) / 60_000L);
        if (minutes < 1L) {
            return "just now";
        }
        if (minutes < 60L) {
            return minutes + " min ago";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + (hours == 1L ? " hour ago" : " hours ago");
        }
        long days = hours / 24L;
        return days + (days == 1L ? " day ago" : " days ago");
    }

    Optional<UpdateSource> findMatchingSource(ManagedPlugin plugin) {
        Path path = plugin.getPath();
        if (path == null) {
            return Optional.empty();
        }

        String fileName = path.getFileName().toString();
        return context.getUpdateSourceRegistry().getSources().stream()
                .filter(source -> source.getTargetDirectory() == TargetDirectory.PLUGINS)
                .filter(source -> fileName.equalsIgnoreCase(source.getFilename()))
                .findFirst();
    }
}
