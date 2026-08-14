package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.persistence.RestartCooldownRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.PluginUpdateSettings;
import eu.nurkert.neverUp2Late.persistence.PluginUpdateSettingsRepository.UpdateBehaviour;
import eu.nurkert.neverUp2Late.plugin.ManagedPlugin;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleException;
import eu.nurkert.neverUp2Late.plugin.PluginLifecycleManager;
import eu.nurkert.neverUp2Late.update.UpdateCompletedEvent;
import eu.nurkert.neverUp2Late.update.UpdateCompletionListener;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.UpdateSource;
import eu.nurkert.neverUp2Late.util.ArchiveUtils;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Set;

public class InstallationHandler implements Listener, UpdateCompletionListener {

    private static final LocalTime PLUGIN_RESTART_WINDOW_START = LocalTime.of(3, 0);
    private static final LocalTime PLUGIN_RESTART_WINDOW_END = LocalTime.of(6, 0);
    private static final Set<String> ALWAYS_ALLOW_IMMEDIATE_RESTART = Set.of("paper", "geyser");

    private final JavaPlugin plugin;
    private final Server server;
    private final List<PostUpdateAction> actions = new CopyOnWriteArrayList<>();
    /**
     * Updates that are already written to disk but whose post-update actions
     * still have to happen. Keyed by destination and kept in arrival order: a
     * single slot silently dropped the first update whenever two sources
     * finished in the same run, and dropped it entirely whenever the restart
     * cooldown declined.
     */
    private final Map<Path, UpdateCompletedEvent> pendingEvents =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final PluginUpdateSettingsRepository updateSettingsRepository;
    private final Clock clock;
    private final Logger logger;
    private BukkitTask deferredRestartTask;
    private BukkitTask retryTask;

    public InstallationHandler(JavaPlugin plugin,
                               PluginLifecycleManager pluginLifecycleManager,
                               PluginUpdateSettingsRepository updateSettingsRepository) {
        this(
                plugin,
                plugin.getServer(),
                new RestartCooldownRepository(plugin.getDataFolder(), plugin.getLogger()),
                plugin.getLogger(),
                pluginLifecycleManager,
                updateSettingsRepository,
                Clock.systemDefaultZone()
        );
    }

    public InstallationHandler(Server server,
                               RestartCooldownRepository restartCooldownRepository,
                               Logger logger) {
        this(server, restartCooldownRepository, logger, null, null, Clock.systemDefaultZone());
    }

    public InstallationHandler(Server server,
                               RestartCooldownRepository restartCooldownRepository,
                               Logger logger,
                               PluginLifecycleManager pluginLifecycleManager,
                               PluginUpdateSettingsRepository updateSettingsRepository) {
        this(server, restartCooldownRepository, logger, pluginLifecycleManager, updateSettingsRepository, Clock.systemDefaultZone());
    }

    InstallationHandler(Server server,
                        RestartCooldownRepository restartCooldownRepository,
                        Logger logger,
                        PluginLifecycleManager pluginLifecycleManager,
                        PluginUpdateSettingsRepository updateSettingsRepository,
                        Clock clock) {
        this(null, server, restartCooldownRepository, logger, pluginLifecycleManager, updateSettingsRepository, clock);
    }

    private InstallationHandler(JavaPlugin plugin,
                                Server server,
                                RestartCooldownRepository restartCooldownRepository,
                                Logger logger,
                                PluginLifecycleManager pluginLifecycleManager,
                                PluginUpdateSettingsRepository updateSettingsRepository,
                                Clock clock) {
        this.plugin = plugin;
        this.server = server;
        this.updateSettingsRepository = updateSettingsRepository;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = logger;
        if (pluginLifecycleManager != null) {
            actions.add(new PluginReloadAction(pluginLifecycleManager, logger, updateSettingsRepository));
        }
        registerAction(new ServerRestartAction(server, restartCooldownRepository, logger));
    }

    public void registerAction(PostUpdateAction action) {
        actions.add(action);
    }

    @Override
    public void onUpdateCompleted(UpdateCompletedEvent event) {
        if (event == null) {
            return;
        }
        remember(event);
        applyPendingEvents(null);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        // Bukkit fires this while the quitting player is still listed as online,
        // so "is anybody left" has to ignore exactly that player - comparing
        // against an empty list meant a deferred restart never happened when the
        // server emptied out.
        //
        // Reloading plugins or shutting the server down from inside quit
        // dispatch would tear things apart while Bukkit is still walking its
        // handler list, so the work waits for the next tick, by which point the
        // player is really gone.
        if (plugin == null) {
            applyPendingEvents(event.getPlayer());
            return;
        }
        applyPendingEvents(event.getPlayer(), true);
    }

    /**
     * Carries out the post-update actions for everything that is waiting, as far
     * as the current situation allows. Anything that could not be applied stays
     * queued and is retried later.
     *
     * @param leavingPlayer player that is in the middle of disconnecting and
     *                      must not count as online, or {@code null}
     */
    private void applyPendingEvents(Player leavingPlayer) {
        applyPendingEvents(leavingPlayer, false);
    }

    private void applyPendingEvents(Player leavingPlayer, boolean deferToNextTick) {
        if (deferToNextTick) {
            if (!isServerEmpty(leavingPlayer) || pendingEvents.isEmpty()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> applyPendingEvents(null, false));
            return;
        }
        if (!isServerEmpty(leavingPlayer)) {
            // Players are online: hold everything back, but make sure the
            // maintenance window still gets a chance to fire on its own.
            if (deferredRestartTask == null) {
                for (UpdateCompletedEvent waiting : snapshotPending()) {
                    if (shouldDeferForPluginWindow(waiting)) {
                        scheduleDeferredRestart(waiting);
                        break;
                    }
                }
            }
            return;
        }

        boolean deferred = false;
        for (UpdateCompletedEvent event : snapshotPending()) {
            if (shouldDeferForPluginWindow(event)) {
                scheduleDeferredRestart(event);
                deferred = true;
                continue;
            }
            if (runPostUpdateActions(event)) {
                forget(event);
                if (restartWasTriggered()) {
                    // The server is on its way down. Everything still queued is
                    // already on disk and applies on the next start; touching
                    // more plugins during shutdown would only cause trouble.
                    break;
                }
            } else {
                // Nothing applied it - most commonly the restart cooldown. Keep
                // it queued and come back when the cooldown has expired.
                scheduleRetryAfterCooldown();
            }
        }
        if (!deferred && pendingEvents.isEmpty()) {
            cancelDeferredRestartTask();
        }
    }

    /**
     * @return {@code true} if an action took responsibility for the update, so
     *         it no longer has to be remembered
     */
    private boolean runPostUpdateActions(UpdateCompletedEvent event) {
        for (PostUpdateAction action : actions) {
            try {
                if (action.execute(event)) {
                    continue;
                }
                // The action stopped the chain. That means "handled" for every
                // action except a restart that the cooldown declined.
                if (action instanceof ServerRestartAction restart) {
                    return restart.didRestart();
                }
                return true;
            } catch (Exception ex) {
                server.getLogger().log(Level.SEVERE, "Failed to execute post update action " + action, ex);
            }
        }
        return false;
    }

    private boolean restartWasTriggered() {
        for (PostUpdateAction action : actions) {
            if (action instanceof ServerRestartAction restart && restart.didRestart()) {
                return true;
            }
        }
        return false;
    }

    private boolean isServerEmpty(Player leavingPlayer) {
        for (Player online : server.getOnlinePlayers()) {
            if (online == leavingPlayer) {
                continue;
            }
            return false;
        }
        return true;
    }

    private void remember(UpdateCompletedEvent event) {
        pendingEvents.put(event.getDestination(), event);
    }

    private void forget(UpdateCompletedEvent event) {
        pendingEvents.remove(event.getDestination());
    }

    private List<UpdateCompletedEvent> snapshotPending() {
        synchronized (pendingEvents) {
            return List.copyOf(pendingEvents.values());
        }
    }

    private void scheduleRetryAfterCooldown() {
        if (plugin == null || retryTask != null) {
            return;
        }
        long remaining = actions.stream()
                .filter(ServerRestartAction.class::isInstance)
                .map(ServerRestartAction.class::cast)
                .mapToLong(action -> action.remainingCooldownMillis(System.currentTimeMillis()))
                .max()
                .orElse(0L);
        long ticks = Math.max(20L, (remaining + 49L) / 50L);
        retryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            retryTask = null;
            applyPendingEvents(null);
        }, ticks);
    }

    void tryExecutePendingEvent() {
        applyPendingEvents(null);
    }

    private boolean shouldDeferForPluginWindow(UpdateCompletedEvent event) {
        if (event == null || event.getSource() == null) {
            return false;
        }
        if (event.getSource().getTargetDirectory() != TargetDirectory.PLUGINS) {
            return false;
        }
        String sourceName = event.getSource().getName();
        if (sourceName != null) {
            for (String allowed : ALWAYS_ALLOW_IMMEDIATE_RESTART) {
                if (allowed.equalsIgnoreCase(sourceName)) {
                    return false;
                }
            }
        }
        LocalTime currentTime = LocalDateTime.now(clock).toLocalTime();
        return !isWithinPluginRestartWindow(currentTime);
    }

    private boolean isWithinPluginRestartWindow(LocalTime time) {
        if (PLUGIN_RESTART_WINDOW_START.equals(PLUGIN_RESTART_WINDOW_END)) {
            return true;
        }
        if (PLUGIN_RESTART_WINDOW_START.isBefore(PLUGIN_RESTART_WINDOW_END)) {
            return !time.isBefore(PLUGIN_RESTART_WINDOW_START) && time.isBefore(PLUGIN_RESTART_WINDOW_END);
        }
        return !time.isBefore(PLUGIN_RESTART_WINDOW_START) || time.isBefore(PLUGIN_RESTART_WINDOW_END);
    }

    private void scheduleDeferredRestart(UpdateCompletedEvent event) {
        if (event == null) {
            return;
        }

        Duration delay = timeUntilNextWindow(LocalDateTime.now(clock));
        if (logger != null) {
            String sourceName = event.getSource() != null ? event.getSource().getName() : "unknown";
            long hours = delay.toHours();
            long minutes = delay.minusHours(hours).toMinutes();
            logger.log(Level.INFO,
                    "Deferring server restart for plugin update {0} until maintenance window (03:00-06:00). Next attempt in {1} hour(s) and {2} minute(s).",
                    new Object[]{sourceName, hours, minutes});
        }

        if (plugin == null) {
            return;
        }

        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        if (scheduler == null) {
            return;
        }

        cancelDeferredRestartTask();
        long ticks = Math.max(1L, (delay.toMillis() + 49) / 50L);
        deferredRestartTask = scheduler.runTaskLater(plugin, this::tryExecutePendingEvent, ticks);
    }

    private Duration timeUntilNextWindow(LocalDateTime currentDateTime) {
        LocalDate currentDate = currentDateTime.toLocalDate();
        LocalDateTime windowStartToday = currentDate.atTime(PLUGIN_RESTART_WINDOW_START);
        LocalDateTime windowEndToday = currentDate.atTime(PLUGIN_RESTART_WINDOW_END);

        if (isWithinPluginRestartWindow(currentDateTime.toLocalTime())) {
            return Duration.ZERO;
        }

        if (currentDateTime.isBefore(windowStartToday)) {
            return Duration.between(currentDateTime, windowStartToday);
        }

        if (currentDateTime.isBefore(windowEndToday)) {
            return Duration.ZERO;
        }

        LocalDateTime nextWindowStart = windowStartToday.plusDays(1);
        return Duration.between(currentDateTime, nextWindowStart);
    }

    private void cancelDeferredRestartTask() {
        if (deferredRestartTask != null) {
            deferredRestartTask.cancel();
            deferredRestartTask = null;
        }
    }

    public interface PostUpdateAction {
        boolean execute(UpdateCompletedEvent event) throws Exception;
    }

    public static class ServerRestartAction implements PostUpdateAction {
        private final Server server;
        private final RestartCooldownRepository restartCooldownRepository;
        private final Logger logger;
        private static final long RESTART_COOLDOWN_MILLIS = Duration.ofHours(1).toMillis();
        private final AtomicLong lastRestartTime;
        private volatile boolean restarted;

        public ServerRestartAction(Server server,
                                   RestartCooldownRepository restartCooldownRepository,
                                   Logger logger) {
            this.server = server;
            this.restartCooldownRepository = restartCooldownRepository;
            this.logger = logger;
            this.lastRestartTime = new AtomicLong(restartCooldownRepository.getLastRestartTime());
        }

        /**
         * Whether the last {@link #execute} really shut the server down. Both
         * outcomes return {@code false}, but only a real restart applies the
         * update - a cooldown skip has to be retried later.
         */
        public boolean didRestart() {
            return restarted;
        }

        /** Milliseconds left before a restart is permitted again. */
        public long remainingCooldownMillis(long now) {
            long lastRestart = lastRestartTime.get();
            if (lastRestart == 0L) {
                return 0L;
            }
            long elapsed = now - lastRestart;
            if (elapsed < 0L) {
                // The clock moved backwards; do not lock restarts out for hours.
                return 0L;
            }
            return Math.max(0L, RESTART_COOLDOWN_MILLIS - elapsed);
        }

        @Override
        public boolean execute(UpdateCompletedEvent event) {
            restarted = false;
            long now = System.currentTimeMillis();

            while (true) {
                long lastRestart = lastRestartTime.get();
                long elapsed = now - lastRestart;

                if (lastRestart != 0L && elapsed >= 0L && elapsed < RESTART_COOLDOWN_MILLIS) {
                    long remainingMillis = RESTART_COOLDOWN_MILLIS - elapsed;
                    Duration remaining = Duration.ofMillis(remainingMillis);
                    long minutes = remaining.toMinutes();
                    long seconds = remaining.minusMinutes(minutes).getSeconds();

                    logger.log(
                            Level.INFO,
                            String.format(
                                    "Skipping server restart; cooldown active for %d minute(s) and %d second(s).",
                                    minutes,
                                    seconds
                            )
                    );
                    return false;
                }

                if (lastRestartTime.compareAndSet(lastRestart, now)) {
                    restartCooldownRepository.saveLastRestartTime(now);
                    break;
                }
            }

            logger.log(Level.INFO, "Restarting server to complete plugin update.");
            restarted = true;
            server.shutdown();
            return false;
        }

        @Override
        public String toString() {
            return "ServerRestartAction";
        }
    }

    static class PluginReloadAction implements PostUpdateAction {
        private final PluginLifecycleManager lifecycleManager;
        private final Logger logger;
        private final PluginUpdateSettingsRepository updateSettingsRepository;

        PluginReloadAction(PluginLifecycleManager lifecycleManager,
                           Logger logger,
                           PluginUpdateSettingsRepository updateSettingsRepository) {
            this.lifecycleManager = lifecycleManager;
            this.logger = logger;
            this.updateSettingsRepository = updateSettingsRepository;
        }

        @Override
        public boolean execute(UpdateCompletedEvent event) {
            UpdateSource source = event.getSource();
            if (source == null || source.getTargetDirectory() != TargetDirectory.PLUGINS) {
                return true;
            }
            Path destination = event.getDestination();
            if (destination == null) {
                return true;
            }

            ManagedPlugin managed = lifecycleManager.findByPath(destination).orElse(null);

            // The gate has to be evaluated for every reload, not only when the
            // plugin happens to be known by path. It used to sit behind that
            // lookup, so a jar we did not recognise - a freshly renamed one, for
            // instance - was hot-reloaded even though its setting said "restart
            // required", and the default setting is exactly that.
            String pluginName = managed != null
                    ? managed.getName()
                    : ArchiveUtils.getPluginInfo(destination).map(ArchiveUtils.PluginInfo::name).orElse(null);
            if (pluginName == null) {
                if (logger != null) {
                    logger.log(Level.FINE, "Cannot identify the plugin in {0}; leaving it to the restart.",
                            destination);
                }
                return true;
            }
            if (updateSettingsRepository != null) {
                PluginUpdateSettings settings = updateSettingsRepository.getSettings(pluginName);
                if (settings.behaviour() != UpdateBehaviour.AUTO_RELOAD) {
                    return true;
                }
            }

            if (managed != null && isSelf(managed)) {
                // Reloading NeverUp2Late from inside itself closes the class
                // loader this very code runs in.
                if (logger != null) {
                    logger.log(Level.FINE, "Skipping self-reload of {0}; a restart applies it safely.", pluginName);
                }
                return true;
            }

            try {
                boolean reloaded = lifecycleManager.reloadPlugin(destination);
                if (reloaded) {
                    if (logger != null) {
                        logger.log(Level.INFO,
                                "Reloaded plugin from {0} without requiring a server restart.",
                                event.getDestination());
                    }
                    return false;
                }
            } catch (PluginLifecycleException ex) {
                if (logger != null) {
                    logger.log(Level.WARNING,
                            "Failed to reload plugin from {0}: {1}",
                            new Object[]{event.getDestination(), ex.getMessage()});
                    logger.log(Level.FINE, "Plugin reload failure", ex);
                }
            }
            return true;
        }

        private boolean isSelf(ManagedPlugin managed) {
            return managed.getPlugin()
                    .map(loaded -> loaded.getClass().getClassLoader() == getClass().getClassLoader())
                    .orElse(false);
        }

        @Override
        public String toString() {
            return "PluginReloadAction";
        }
    }
}
