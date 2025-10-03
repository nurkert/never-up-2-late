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
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
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
    private volatile UpdateCompletedEvent pendingEvent;
    private final PluginUpdateSettingsRepository updateSettingsRepository;
    private final Clock clock;
    private final Logger logger;
    private BukkitTask deferredRestartTask;

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
        if (!server.getOnlinePlayers().isEmpty()) {
            pendingEvent = event;
            if (shouldDeferForPluginWindow(event)) {
                scheduleDeferredRestart(event);
            }
            return;
        }
        executeActions(event);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        if (server.getOnlinePlayers().size() <= 1) {
            tryExecutePendingEvent();
        }
    }

    private void executeActions(UpdateCompletedEvent event) {
        if (shouldDeferForPluginWindow(event)) {
            pendingEvent = event;
            scheduleDeferredRestart(event);
            return;
        }
        pendingEvent = null;
        cancelDeferredRestartTask();
        runPostUpdateActions(event);
    }

    private void runPostUpdateActions(UpdateCompletedEvent event) {
        for (PostUpdateAction action : actions) {
            try {
                boolean shouldContinue = action.execute(event);
                if (!shouldContinue) {
                    break;
                }
            } catch (Exception ex) {
                server.getLogger().log(Level.SEVERE, "Failed to execute post update action " + action, ex);
            }
        }
    }

    void tryExecutePendingEvent() {
        UpdateCompletedEvent pending = pendingEvent;
        if (pending == null) {
            return;
        }
        if (!server.getOnlinePlayers().isEmpty()) {
            return;
        }
        if (shouldDeferForPluginWindow(pending)) {
            scheduleDeferredRestart(pending);
            return;
        }
        pendingEvent = null;
        cancelDeferredRestartTask();
        runPostUpdateActions(pending);
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

        public ServerRestartAction(Server server,
                                   RestartCooldownRepository restartCooldownRepository,
                                   Logger logger) {
            this.server = server;
            this.restartCooldownRepository = restartCooldownRepository;
            this.logger = logger;
            this.lastRestartTime = new AtomicLong(restartCooldownRepository.getLastRestartTime());
        }

        @Override
        public boolean execute(UpdateCompletedEvent event) {
            long now = System.currentTimeMillis();

            while (true) {
                long lastRestart = lastRestartTime.get();
                long elapsed = now - lastRestart;

                if (lastRestart != 0L && elapsed < RESTART_COOLDOWN_MILLIS) {
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
            if (event.getDestination() == null) {
                return true;
            }
            try {
                ManagedPlugin plugin = lifecycleManager.findByPath(event.getDestination()).orElse(null);
                if (plugin != null && updateSettingsRepository != null) {
                    PluginUpdateSettings settings = updateSettingsRepository.getSettings(plugin.getName());
                    if (settings.behaviour() != UpdateBehaviour.AUTO_RELOAD) {
                        return true;
                    }
                }
                boolean reloaded = lifecycleManager.reloadPlugin(event.getDestination());
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

        @Override
        public String toString() {
            return "PluginReloadAction";
        }
    }
}
