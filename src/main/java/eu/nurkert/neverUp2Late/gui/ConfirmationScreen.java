package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Feedback;
import eu.nurkert.neverUp2Late.util.Theme;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Asks before anything that cannot be taken back: a small chest with what is
 * about to happen in the middle, a green side to go ahead and a red side to
 * step back.
 *
 * <p>This replaces typing "yes" in chat, which closed the menu, could be
 * answered by a stray message and left the operator reading the question in
 * one place and answering it in another.</p>
 */
final class ConfirmationScreen {

    private static final int SIZE = 27;
    private static final int DESCRIPTION_SLOT = 13;
    private static final int CONFIRM_CORE_SLOT = 10;
    private static final int CANCEL_CORE_SLOT = 16;

    /**
     * One question.
     *
     * @param screenName   short name for the title, e.g. "Remove plugin"
     * @param description  the item in the middle, saying exactly what happens
     * @param confirmLabel what the green side says it does
     * @param onConfirm    the unchanged action, run once the green side is clicked
     * @param onCancel     where the red side leads back to
     */
    record Confirmation(String screenName,
                        ItemStack description,
                        String confirmLabel,
                        Consumer<Player> onConfirm,
                        Consumer<Player> onCancel) {

        Confirmation {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(onConfirm, "onConfirm");
            Objects.requireNonNull(onCancel, "onCancel");
        }
    }

    private final GuiState state;

    ConfirmationScreen(GuiState state) {
        this.state = state;
    }

    void open(Player player, Confirmation confirmation) {
        Inventory inventory = Bukkit.createInventory(null, SIZE, GuiChrome.title(confirmation.screenName()));

        ItemStack confirm = GuiChrome.button(Material.LIME_STAINED_GLASS_PANE,
                Theme.SUCCESS + Theme.CHECK + " Confirm",
                List.of(Theme.MUTED + confirmation.confirmLabel(), " ",
                        GuiChrome.hints("Click: go ahead")));
        ItemStack confirmCore = GuiChrome.button(Material.LIME_CONCRETE,
                Theme.SUCCESS + Theme.CHECK + " Confirm",
                List.of(Theme.MUTED + confirmation.confirmLabel(), " ",
                        GuiChrome.hints("Click: go ahead")));
        ItemStack cancel = GuiChrome.button(Material.RED_STAINED_GLASS_PANE,
                Theme.ERROR + Theme.CROSS + " Cancel",
                List.of(Theme.MUTED + "Nothing is changed.", " ", GuiChrome.hints("Click: go back")));
        ItemStack cancelCore = GuiChrome.button(Material.RED_CONCRETE,
                Theme.ERROR + Theme.CROSS + " Cancel",
                List.of(Theme.MUTED + "Nothing is changed.", " ", GuiChrome.hints("Click: go back")));
        ItemStack frame = GuiChrome.pane(FrameState.NEUTRAL);

        for (int slot = 0; slot < SIZE; slot++) {
            Side side = sideOf(slot);
            // The centre of each side is solid, the rest glass around it.
            boolean core = slot == CONFIRM_CORE_SLOT || slot == CANCEL_CORE_SLOT;
            inventory.setItem(slot, switch (side) {
                case CONFIRM -> (core ? confirmCore : confirm).clone();
                case CANCEL -> (core ? cancelCore : cancel).clone();
                case NONE -> frame.clone();
            });
        }
        inventory.setItem(DESCRIPTION_SLOT, confirmation.description());

        state.openInventories.put(player.getUniqueId(), InventorySession.confirmation(inventory, confirmation));
        player.openInventory(inventory);
        Feedback.attention(player);
    }

    void handleClick(Player player, InventorySession session, InventoryClickEvent event) {
        Confirmation confirmation = session.confirmation();
        if (confirmation == null) {
            return;
        }
        switch (sideOf(event.getRawSlot())) {
            case CONFIRM -> {
                Feedback.click(player);
                // Forget the question before acting, so a second click on the
                // still-open chest cannot run the action twice.
                state.openInventories.remove(player.getUniqueId());
                confirmation.onConfirm().accept(player);
            }
            case CANCEL -> {
                Feedback.pageTurn(player);
                player.sendMessage(Theme.MUTED + "Cancelled - nothing was changed.");
                confirmation.onCancel().accept(player);
            }
            case NONE -> {
            }
        }
    }

    private enum Side { CONFIRM, CANCEL, NONE }

    /** The left three columns confirm, the right three cancel - big targets, hard to miss. */
    private static Side sideOf(int slot) {
        if (slot < 0 || slot >= SIZE) {
            return Side.NONE;
        }
        int column = slot % GuiLayout.ROW;
        if (column <= 2) {
            return Side.CONFIRM;
        }
        if (column >= 6) {
            return Side.CANCEL;
        }
        return Side.NONE;
    }
}
