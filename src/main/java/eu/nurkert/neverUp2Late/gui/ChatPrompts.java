package eu.nurkert.neverUp2Late.gui;

import eu.nurkert.neverUp2Late.util.Theme;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chat questions the GUI is waiting on an answer for, and how long each one keeps listening.
 */
final class ChatPrompts {

    private static final long PROMPT_TIMEOUT_MILLIS = 60_000L;

    final Map<UUID, LinkRequest> pendingLinkRequests = new ConcurrentHashMap<>();
    final Map<UUID, ManagedPlugin> pendingRemovalRequests = new ConcurrentHashMap<>();
    final Map<UUID, ManagedPlugin> pendingSuggestionRequests = new ConcurrentHashMap<>();
    /**
     * When each pending chat prompt stops listening. Without it the listener
     * stayed armed forever and swallowed whatever the operator said next -
     * minutes later, about something else entirely.
     */
    final Map<UUID, Long> promptDeadlines = new ConcurrentHashMap<>();
    final Set<UUID> pendingCleanupConfirmations = ConcurrentHashMap.newKeySet();

    boolean hasPromptExpired(UUID playerId) {
        Long deadline = promptDeadlines.get(playerId);
        if (deadline == null) {
            return false;
        }
        if (!hasPendingPrompt(playerId)) {
            // A prompt that was answered elsewhere leaves its deadline behind.
            // Reporting a timeout for it would claim something expired when it
            // had in fact completed.
            promptDeadlines.remove(playerId);
            return false;
        }
        return System.currentTimeMillis() > deadline;
    }

    private boolean hasPendingPrompt(UUID playerId) {
        return pendingCleanupConfirmations.contains(playerId)
                || pendingRemovalRequests.containsKey(playerId)
                || pendingLinkRequests.containsKey(playerId)
                || pendingSuggestionRequests.containsKey(playerId);
    }

    /** Arms a chat prompt, and states the deadline so nobody is left guessing. */
    void armPrompt(Player player) {
        // Starting any prompt cancels whatever was pending before, so the answer
        // can never be routed to a question the operator has moved on from.
        UUID id = player.getUniqueId();
        pendingCleanupConfirmations.remove(id);
        pendingLinkRequests.remove(id);
        pendingRemovalRequests.remove(id);
        pendingSuggestionRequests.remove(id);
        promptDeadlines.put(player.getUniqueId(), System.currentTimeMillis() + PROMPT_TIMEOUT_MILLIS);
        player.sendMessage(Theme.SUBTLE + "You have 60 seconds; type "
                + Theme.MUTED + "cancel" + Theme.SUBTLE + " to abort.");
    }

    void clearPrompts(UUID playerId) {
        promptDeadlines.remove(playerId);
        pendingCleanupConfirmations.remove(playerId);
        pendingLinkRequests.remove(playerId);
        pendingRemovalRequests.remove(playerId);
        pendingSuggestionRequests.remove(playerId);
    }

    record LinkRequest(String pluginName, boolean standalone) {
    }
}
