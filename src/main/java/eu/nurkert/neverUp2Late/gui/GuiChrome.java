package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Theme;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The parts every screen shares: titles, the coloured frame and the footer
 * navigation. Screens only decide what goes into the content area.
 */
final class GuiChrome {

    private static final String BRAND = Theme.gradient("NeverUp2Late", 0x3DD6D0, 0x3B82F6);
    /** How much of a screen name fits next to the brand before the title runs off. */
    private static final int MAX_SCREEN_NAME = 16;

    /**
     * Tooltip noise nobody asked for. Looked up by name: which flags exist
     * differs between server versions, and a missing constant must cost a
     * hidden line, not the whole menu.
     */
    private static final ItemFlag[] QUIET_FLAGS = resolveFlags(
            "HIDE_ATTRIBUTES", "HIDE_ADDITIONAL_TOOLTIP", "HIDE_ENCHANTS", "HIDE_STORED_ENCHANTS");

    private GuiChrome() {
    }

    /** "NeverUp2Late › Plugins", with the brand as a gradient. */
    static String title(String screen) {
        return BRAND + Theme.SUBTLE + " › " + Theme.TITLE_LIGHT + GuiLayout.shorten(screen, MAX_SCREEN_NAME);
    }

    /** Fills the header and footer rows with blank panes in the colour of {@code state}. */
    static void drawFrame(Inventory inventory, FrameState state) {
        ItemStack pane = pane(state);
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            boolean header = slot < GuiLayout.ROW;
            boolean footer = slot >= size - GuiLayout.ROW;
            if (header || footer) {
                inventory.setItem(slot, pane.clone());
            }
        }
    }

    /** A pane with a blank name, so hovering the frame shows no tooltip at all. */
    static ItemStack pane(FrameState state) {
        return pane(state.pane());
    }

    static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            quiet(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            quiet(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Hides attribute and "additional" tooltip lines that only clutter a menu item. */
    static void quiet(ItemMeta meta) {
        if (QUIET_FLAGS.length > 0) {
            meta.addItemFlags(QUIET_FLAGS);
        }
    }

    /**
     * Forces the enchantment shimmer on or off. Off matters as much as on: an
     * enchanted book shimmers by itself, and the shimmer is meant to say
     * "update ready" and nothing else.
     */
    static void glint(ItemMeta meta, boolean on) {
        try {
            meta.setEnchantmentGlintOverride(on);
        } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
            // Older servers have no override; the lore still says it.
        }
    }

    /** "Left-click: details · Right-click: check now", in the quietest colour. */
    static String hints(String... hints) {
        return Theme.SUBTLE + String.join(" " + Theme.DOT + " ", hints);
    }

    static ItemStack back(String target) {
        return button(Material.ARROW, Theme.ACCENT + "← Back",
                List.of(Theme.MUTED + "Returns to " + target + "."));
    }

    static ItemStack close() {
        return button(Material.BARRIER, Theme.ERROR + Theme.CROSS + " Close",
                List.of(Theme.MUTED + "Closes the menu."));
    }

    /**
     * Draws previous / page / next. The arrows only appear when there is
     * somewhere to go, so a dead button can never be pressed; the indicator
     * is always there, so "is there more?" never needs guessing.
     */
    static void drawPager(Inventory inventory, Pagination pagination, String countLine) {
        if (pagination.hasPrevious()) {
            inventory.setItem(GuiLayout.PREVIOUS_SLOT, button(Material.SPECTRAL_ARROW,
                    Theme.ACCENT + "‹ Previous page",
                    List.of(Theme.MUTED + "Go to page " + pagination.page() + ".")));
        }
        List<String> lore = new ArrayList<>();
        if (countLine != null && !countLine.isBlank()) {
            lore.add(countLine);
        }
        ItemStack indicator = button(Material.PAPER,
                Theme.TEXT + pagination.label(), lore);
        indicator.setAmount(Math.min(64, pagination.page() + 1));
        inventory.setItem(GuiLayout.PAGE_SLOT, indicator);
        if (pagination.hasNext()) {
            inventory.setItem(GuiLayout.NEXT_SLOT, button(Material.SPECTRAL_ARROW,
                    Theme.ACCENT + "Next page ›",
                    List.of(Theme.MUTED + "Go to page " + (pagination.page() + 2) + ".")));
        }
    }

    /** The search button; {@code query} is the active search or {@code null}. */
    static ItemStack search(String what, String query) {
        List<String> lore = new ArrayList<>();
        if (query == null || query.isBlank()) {
            lore.add(Theme.MUTED + "Find " + what + " by name.");
            lore.add(" ");
            lore.add(hints("Click: search"));
        } else {
            lore.add(Theme.MUTED + "Searching for: " + Theme.ACCENT + query);
            lore.add(" ");
            lore.add(hints("Left-click: change", "Right-click: clear"));
        }
        ItemStack item = button(Material.COMPASS, Theme.ACCENT + "Search", lore);
        if (query != null && !query.isBlank()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                glint(meta, true);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    /** The filter button, listing every filter with the active one highlighted. */
    static ItemStack filter(OverviewFilter active) {
        List<String> lore = new ArrayList<>();
        for (OverviewFilter filter : OverviewFilter.values()) {
            if (filter == active) {
                lore.add(Theme.ACCENT + ChatColor.BOLD + Theme.ARROW + " " + filter.label());
            } else {
                lore.add(Theme.SUBTLE + "   " + filter.label());
            }
        }
        lore.add(" ");
        lore.add(hints("Left-click: next", "Right-click: previous"));
        ItemStack item = button(Material.HOPPER, Theme.ACCENT + "Filter: " + Theme.TEXT + active.label(), lore);
        if (active != OverviewFilter.ALL) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                glint(meta, true);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private static ItemFlag[] resolveFlags(String... names) {
        List<ItemFlag> flags = new ArrayList<>();
        for (String name : names) {
            try {
                flags.add(ItemFlag.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // Not on this server version.
            }
        }
        return flags.toArray(new ItemFlag[0]);
    }
}
