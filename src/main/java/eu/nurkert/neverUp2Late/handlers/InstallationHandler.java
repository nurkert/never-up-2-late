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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InstallationHandler implements Listener, UpdateCompletionListener {

    private final Server server;
    private final List<PostUpdateAction> actions = new CopyOnWriteArrayList<>();
    private volatile UpdateCompletedEvent pendingEvent;
    private final PluginUpdateSettingsRepository updateSettingsRepository;
    private final Logger logger;
    private volatile PluginLifecycleManager pluginLifecycleManager;
    private volatile PluginReloadAction pluginReloadAction;
    private volatile boolean autoLoadOnInstall;

    public InstallationHandler(JavaPlugin plugin,
                               PluginLifecycleManager pluginLifecycleManager,
                               PluginUpdateSettingsRepository updateSettingsRepository,
                               boolean autoLoadOnInstall) {
        this(
                plugin.getServer(),
                new RestartCooldownRepository(plugin.getDataFolder(), plugin.getLogger()),
                plugin.getLogger(),
                pluginLifecycleManager,
                updateSettingsRepository,
                autoLoadOnInstall
        );
    }

    public InstallationHandler(Server server,
                               RestartCooldownRepository restartCooldownRepository,
                               Logger logger) {
        this(server, restartCooldownRepository, logger, null, null, false);
    }

    public InstallationHandler(Server server,
                               RestartCooldownRepository restartCooldownRepository,
                               Logger logger,
                               PluginLifecycleManager pluginLifecycleManager,
                               PluginUpdateSettingsRepository updateSettingsRepository,
                               boolean autoLoadOnInstall) {
        this.server = server;
        this.updateSettingsRepository = updateSettingsRepository;
        this.logger = logger;
        this.autoLoadOnInstall = autoLoadOnInstall;
        if (pluginLifecycleManager != null) {
            attachPluginLifecycleManager(pluginLifecycleManager, updateSettingsRepository);
        }
        registerAction(new ServerRestartAction(server, restartCooldownRepository, logger));
    }

    public synchronized void attachPluginLifecycleManager(PluginLifecycleManager manager,
                                                          PluginUpdateSettingsRepository settingsRepository) {
        this.pluginLifecycleManager = manager;
        if (pluginReloadAction != null) {
            actions.remove(pluginReloadAction);
            pluginReloadAction = null;
        }
        if (manager == null) {
            return;
        }
        PluginReloadAction action = new PluginReloadAction(settingsRepository);
        pluginReloadAction = action;
        actions.add(0, action);
    }

    public synchronized void detachPluginLifecycleManager() {
        pluginLifecycleManager = null;
        if (pluginReloadAction != null) {
            actions.remove(pluginReloadAction);
            pluginReloadAction = null;
        }
    }

    public void setAutoLoadOnInstall(boolean autoLoadOnInstall) {
        this.autoLoadOnInstall = autoLoadOnInstall;
    }

    public boolean isAutoLoadOnInstall() {
        return autoLoadOnInstall;
    }

    public void registerAction(PostUpdateAction action) {
        actions.add(action);
    }

    @Override
    public void onUpdateCompleted(UpdateCompletedEvent event) {
        if (!server.getOnlinePlayers().isEmpty()) {
            pendingEvent = event;
            return;
        }
        executeActions(event);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        UpdateCompletedEvent pending = pendingEvent;
        if (pending != null && server.getOnlinePlayers().size() <= 1) {
            pendingEvent = null;
            executeActions(pending);
        }
    }

    private void executeActions(UpdateCompletedEvent event) {
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

    class PluginReloadAction implements PostUpdateAction {
        private final PluginUpdateSettingsRepository updateSettingsRepository;

        PluginReloadAction(PluginUpdateSettingsRepository updateSettingsRepository) {
            this.updateSettingsRepository = updateSettingsRepository;
        }

        @Override
        public boolean execute(UpdateCompletedEvent event) {
            PluginLifecycleManager lifecycleManager = pluginLifecycleManager;
            if (lifecycleManager == null) {
                return true;
            }
            UpdateSource source = event.getSource();
            if (source == null || source.getTargetDirectory() != TargetDirectory.PLUGINS) {
                return true;
            }
            if (event.getDestination() == null) {
                return true;
            }
            try {
                ManagedPlugin plugin = lifecycleManager.findByPath(event.getDestination()).orElse(null);
                boolean wasLoaded = plugin != null && plugin.isLoaded();
                if (!wasLoaded && !autoLoadOnInstall) {
                    return true;
                }
                if (wasLoaded && updateSettingsRepository != null && plugin != null) {
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
