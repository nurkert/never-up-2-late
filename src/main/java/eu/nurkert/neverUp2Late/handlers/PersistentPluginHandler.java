package eu.nurkert.neverUp2Late.handlers;

import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository;
import eu.nurkert.neverUp2Late.persistence.UpdateStateRepository.PluginState;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public class PersistentPluginHandler {

    private final UpdateStateRepository repository;

    public PersistentPluginHandler(JavaPlugin plugin) {
        this(UpdateStateRepository.forPlugin(plugin));
    }

    public PersistentPluginHandler(UpdateStateRepository repository) {
        this.repository = repository;
    }

    public void saveLatestBuild(String pluginName, int build, String version) {
        repository.saveLatestBuild(pluginName, build, version);
    }

    public int getStoredBuild(String pluginName) {
        return repository.getStoredBuild(pluginName);
    }

    public String getStoredVersion(String pluginName) {
        return repository.getStoredVersion(pluginName);
    }

    public Optional<PluginState> getPluginState(String pluginName) {
        return repository.find(pluginName);
    }

    public boolean hasPluginInfo(String pluginName) {
        return repository.hasPluginInfo(pluginName);
    }
}
