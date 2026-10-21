package eu.nurkert.neverUp2Late.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Sounds and the action bar: feedback that confirms what happened without
 * adding another line to the chat.
 *
 * <p>Sounds are played by name. The {@code Sound} type changed from an enum to
 * an interface in 1.21.3, and a constant compiled against one does not link
 * against the other; the names have stayed the same throughout. Everything
 * here must run on the server thread, and nothing here may ever fail the
 * action it accompanies.</p>
 */
public final class Feedback {

    private Feedback() {
    }

    /** A button or menu entry was pressed. */
    public static void click(Player player) {
        play(player, "minecraft:ui.button.click", 0.35f, 1.4f);
    }

    /** A different page or screen was opened. */
    public static void pageTurn(Player player) {
        play(player, "minecraft:item.book.page_turn", 0.6f, 1.0f);
    }

    /** Something finished the way it should. */
    public static void success(CommandSender sender) {
        if (sender instanceof Player player) {
            play(player, "minecraft:entity.experience_orb.pickup", 0.5f, 1.2f);
        }
    }

    /** Something needs the player's attention: a question, an update waiting. */
    public static void attention(CommandSender sender) {
        if (sender instanceof Player player) {
            play(player, "minecraft:block.note_block.pling", 0.5f, 1.6f);
        }
    }

    /** Something was refused or failed. */
    public static void error(CommandSender sender) {
        if (sender instanceof Player player) {
            play(player, "minecraft:block.note_block.bass", 0.6f, 0.7f);
        }
    }

    /**
     * Shows {@code legacyText} above the hotbar. Meant for progress that would
     * otherwise be a stream of chat lines; the console does not get it.
     */
    public static void actionBar(CommandSender sender, String legacyText) {
        if (!(sender instanceof Player player)) {
            return;
        }
        try {
            if (ChatMessages.adventureAvailable()) {
                AdventureChat.actionBar(player, legacyText);
            } else {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(legacyText));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A courtesy; the chat still carries the result.
        }
    }

    private static void play(Player player, String sound, float volume, float pitch) {
        try {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (RuntimeException ignored) {
            // A missing sound must never break a click.
        }
    }
}
