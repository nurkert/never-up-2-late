package eu.nurkert.neverUp2Late.command;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import eu.nurkert.neverUp2Late.fetcher.UpdateFetcher;
import eu.nurkert.neverUp2Late.fetcher.SpigotFetcher;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry;
import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;

import sun.misc.Unsafe;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickInstallCoordinatorTest {

    @Test
    void extractModrinthSlugReturnsFirstRelevantSegment() {
        Optional<String> slug = QuickInstallCoordinator.extractModrinthSlug(List.of("plugin", "chunky", "versions"));

        assertTrue(slug.isPresent());
        assertEquals("chunky", slug.orElseThrow());
    }

    @Test
    void extractModrinthSlugFallsBackToFirstSegmentWhenNoPrefix() {
        Optional<String> slug = QuickInstallCoordinator.extractModrinthSlug(List.of("chunky", "versions"));

        assertTrue(slug.isPresent());
        assertEquals("chunky", slug.orElseThrow());
    }

    @Test
    void extractModrinthSlugIgnoresCategorySegments() {
        Optional<String> slug = QuickInstallCoordinator.extractModrinthSlug(List.of("datapack", "veinminer"));

        assertTrue(slug.isPresent());
        assertEquals("veinminer", slug.orElseThrow());
    }

    @Test
    void extractModrinthSlugDecodesUrlEncodedSegments() {
        Optional<String> slug = QuickInstallCoordinator.extractModrinthSlug(List.of("plugin", "My%20Plugin"));

        assertTrue(slug.isPresent());
        assertEquals("My Plugin", slug.orElseThrow());
    }

    @Test
    void extractModrinthSlugReturnsEmptyWhenSlugMissing() {
        Optional<String> slug = QuickInstallCoordinator.extractModrinthSlug(List.of("plugin"));

        assertFalse(slug.isPresent());
    }

    @Test
    void extractCurseforgeProjectReturnsSlugFromBasePath() {
        Optional<QuickInstallCoordinator.CurseforgeProjectPath> project =
                QuickInstallCoordinator.extractCurseforgeProject(List.of("minecraft", "bukkit-plugins", "treetimber"));

        assertTrue(project.isPresent());
        QuickInstallCoordinator.CurseforgeProjectPath path = project.orElseThrow();
        assertEquals(2, path.slugIndex());
        assertEquals("treetimber", path.slug());
    }

    @Test
    void extractCurseforgeProjectSkipsDownloadSegments() {
        Optional<QuickInstallCoordinator.CurseforgeProjectPath> project = QuickInstallCoordinator.extractCurseforgeProject(
                List.of("minecraft", "bukkit-plugins", "treetimber", "download", "5102550")
        );

        assertTrue(project.isPresent());
        QuickInstallCoordinator.CurseforgeProjectPath path = project.orElseThrow();
        assertEquals(2, path.slugIndex());
        assertEquals("treetimber", path.slug());
    }

    @Test
    void extractCurseforgeProjectDecodesSlug() {
        Optional<QuickInstallCoordinator.CurseforgeProjectPath> project = QuickInstallCoordinator.extractCurseforgeProject(
                List.of("minecraft", "bukkit-plugins", "My%20Plugin", "files", "12345")
        );

        assertTrue(project.isPresent());
        QuickInstallCoordinator.CurseforgeProjectPath path = project.orElseThrow();
        assertEquals(2, path.slugIndex());
        assertEquals("My Plugin", path.slug());
    }

    @Test
    void extractCurseforgeProjectReturnsEmptyWhenMissingSlug() {
        Optional<QuickInstallCoordinator.CurseforgeProjectPath> project =
                QuickInstallCoordinator.extractCurseforgeProject(List.of("minecraft", "bukkit-plugins"));

        assertFalse(project.isPresent());
    }

    @Test
    void extractOwnerAndSlugSkipsPrefixesForHangar() {
        Optional<QuickInstallCoordinator.OwnerSlug> result = QuickInstallCoordinator.extractOwnerAndSlug(
                List.of("project", "PaperMC", "Paper", "versions"),
                List.of("plugins", "plugin", "project")
        );

        assertTrue(result.isPresent());
        QuickInstallCoordinator.OwnerSlug ownerSlug = result.orElseThrow();
        assertEquals("PaperMC", ownerSlug.owner());
        assertEquals("Paper", ownerSlug.slug());
    }

    @Test
    void extractOwnerAndSlugSkipsPrefixesForGithub() {
        Optional<QuickInstallCoordinator.OwnerSlug> result = QuickInstallCoordinator.extractOwnerAndSlug(
                List.of("repos", "PaperMC", "Paper", "releases"),
                List.of("repos", "projects", "users", "orgs")
        );

        assertTrue(result.isPresent());
        QuickInstallCoordinator.OwnerSlug ownerSlug = result.orElseThrow();
        assertEquals("PaperMC", ownerSlug.owner());
        assertEquals("Paper", ownerSlug.slug());
    }

    @Test
    void extractOwnerAndSlugReturnsEmptyWhenSlugMissing() {
        Optional<QuickInstallCoordinator.OwnerSlug> result = QuickInstallCoordinator.extractOwnerAndSlug(
                List.of("project", "onlyOwner"),
                List.of("project")
        );

        assertFalse(result.isPresent());
    }

    @Test
    void extractSpigotResourceParsesSlugAndId() {
        Optional<QuickInstallCoordinator.SpigotResource> result = QuickInstallCoordinator.extractSpigotResource(
                List.of("resources", "chunky.81534", "download")
        );

        assertTrue(result.isPresent());
        QuickInstallCoordinator.SpigotResource resource = result.orElseThrow();
        assertEquals(81534L, resource.resourceId());
        assertEquals("chunky", resource.slug());
    }

    @Test
    void extractSpigotResourceFallsBackForNumericPaths() {
        Optional<QuickInstallCoordinator.SpigotResource> result = QuickInstallCoordinator.extractSpigotResource(
                List.of("resources", "81534", "download")
        );

        assertTrue(result.isPresent());
        QuickInstallCoordinator.SpigotResource resource = result.orElseThrow();
        assertEquals(81534L, resource.resourceId());
        assertEquals("resource-81534", resource.slug());
    }

    @Test
    void analyseCreatesSpigotPlanWithCompatibilityPreference() throws Exception {
        QuickInstallCoordinator coordinator = newCoordinator(true);
        Method analyse = QuickInstallCoordinator.class.getDeclaredMethod("analyse", URI.class, String.class);
        analyse.setAccessible(true);

        String url = "https://www.spigotmc.org/resources/chunky.81534/download?version=123456";
        Object plan = analyse.invoke(coordinator, URI.create(url), url);

        Method getFetcherType = plan.getClass().getDeclaredMethod("getFetcherType");
        getFetcherType.setAccessible(true);
        assertEquals("spigot", getFetcherType.invoke(plan));

        Method getTargetDirectory = plan.getClass().getDeclaredMethod("getTargetDirectory");
        getTargetDirectory.setAccessible(true);
        assertEquals(TargetDirectory.PLUGINS, getTargetDirectory.invoke(plan));

        Method getOptions = plan.getClass().getDeclaredMethod("getOptions");
        getOptions.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) getOptions.invoke(plan);
        assertEquals(81534L, options.get("resourceId"));
        assertEquals(Boolean.TRUE, options.get("ignoreCompatibilityWarnings"));

        Method getDefaultFilename = plan.getClass().getDeclaredMethod("getDefaultFilename");
        getDefaultFilename.setAccessible(true);
        assertEquals("chunky.jar", getDefaultFilename.invoke(plan));

        Method getPluginNameCandidates = plan.getClass().getDeclaredMethod("getPluginNameCandidates");
        getPluginNameCandidates.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> candidates = (Set<String>) getPluginNameCandidates.invoke(plan);
        assertTrue(candidates.contains("chunky"));
        assertTrue(candidates.contains("Chunky"));
    }

    @Test
    void determineFilenameFallsBackWhenDownloadNameGeneric() throws Exception {
        QuickInstallCoordinator coordinator = newCoordinator(false);
        Method method = QuickInstallCoordinator.class.getDeclaredMethod(
                "determineFilename",
                String.class,
                String.class
        );
        method.setAccessible(true);

        String fallback = "chunky.jar";
        String result = (String) method.invoke(coordinator,
                "https://api.spiget.org/v2/resources/chunky.81534/download",
                fallback);

        assertEquals(fallback, result);
    }

    @Test
    void deduplicateExistingSourcesRemovesDuplicateEntries() throws Exception {
        QuickInstallCoordinator coordinator = newCoordinator(false);
        UpdateSourceRegistry registry = new UpdateSourceRegistry(Logger.getLogger("test"), new YamlConfiguration());

        CopyOnWriteArrayList<UpdateSourceRegistry.UpdateSource> sources = new CopyOnWriteArrayList<>();
        sources.add(new UpdateSourceRegistry.UpdateSource(
                "wildchests",
                null,
                TargetDirectory.PLUGINS,
                "WildChests.jar",
                "WildChests"));
        sources.add(new UpdateSourceRegistry.UpdateSource(
                "wildchests-1",
                null,
                TargetDirectory.PLUGINS,
                "WildChests.jar",
                "WildChests"));

        setField(registry, "sources", sources);
        setField(coordinator, "updateSourceRegistry", registry);

        Method method = QuickInstallCoordinator.class.getDeclaredMethod("deduplicateExistingSources", CommandSender.class);
        method.setAccessible(true);
        method.invoke(coordinator, new Object[]{null});

        List<UpdateSourceRegistry.UpdateSource> remaining = registry.getSources();
        assertEquals(1, remaining.size());
        assertEquals("wildchests", remaining.get(0).getName());
    }

    @Test
    void findConflictingSourcesDetectsExistingByInstalledPlugin() throws Exception {
        QuickInstallCoordinator coordinator = newCoordinator(false);
        UpdateSourceRegistry registry = new UpdateSourceRegistry(Logger.getLogger("test"), new YamlConfiguration());

        CopyOnWriteArrayList<UpdateSourceRegistry.UpdateSource> sources = new CopyOnWriteArrayList<>();
        UpdateSourceRegistry.UpdateSource existing = new UpdateSourceRegistry.UpdateSource(
                "wildchests",
                null,
                TargetDirectory.PLUGINS,
                "WildChests.jar",
                "WildChests");
        sources.add(existing);

        setField(registry, "sources", sources);
        setField(coordinator, "updateSourceRegistry", registry);

        Class<?> planClass = null;
        for (Class<?> inner : QuickInstallCoordinator.class.getDeclaredClasses()) {
            if ("InstallationPlan".equals(inner.getSimpleName())) {
                planClass = inner;
                break;
            }
        }
        if (planClass == null) {
            throw new IllegalStateException("InstallationPlan class not found");
        }

        Constructor<?> constructor = null;
        for (Constructor<?> candidate : planClass.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 10) {
                constructor = candidate;
                constructor.setAccessible(true);
                break;
            }
        }
        if (constructor == null) {
            throw new IllegalStateException("InstallationPlan constructor not found");
        }

        Map<String, Object> options = new LinkedHashMap<>();
        Object plan = constructor.newInstance(
                coordinator,
                "https://example.com",
                "Provider",
                "host",
                "spigot",
                "wildchests",
                "WildChests",
                TargetDirectory.PLUGINS,
                options,
                "WildChests.jar");

        Method setFilename = planClass.getDeclaredMethod("setFilename", String.class);
        setFilename.setAccessible(true);
        setFilename.invoke(plan, "WildChests.jar");

        Method setInstalledPluginName = planClass.getDeclaredMethod("setInstalledPluginName", String.class);
        setInstalledPluginName.setAccessible(true);
        setInstalledPluginName.invoke(plan, "WildChests");

        Method addPluginNameCandidate = planClass.getDeclaredMethod("addPluginNameCandidate", String.class);
        addPluginNameCandidate.setAccessible(true);
        addPluginNameCandidate.invoke(plan, "WildChests");

        Method findConflicts = QuickInstallCoordinator.class.getDeclaredMethod("findConflictingSources", planClass);
        findConflicts.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<UpdateSourceRegistry.UpdateSource> conflicts =
                (List<UpdateSourceRegistry.UpdateSource>) findConflicts.invoke(coordinator, plan);

        assertEquals(1, conflicts.size());
        assertEquals(existing.getName(), conflicts.get(0).getName());
    }

    @Test
    void fetcherMatchesPlanRecognisesFetcherTypeBySimpleName() throws Exception {
        QuickInstallCoordinator coordinator = newCoordinator(false);
        Object plan = newInstallationPlan(coordinator, "spigot", "timber", "Timber");

        SpigotFetcher.Config config = SpigotFetcher.builder(1).build();
        UpdateSourceRegistry.UpdateSource source = new UpdateSourceRegistry.UpdateSource(
                "timber",
                new SpigotFetcher(config),
                TargetDirectory.PLUGINS,
                "Timber.jar",
                "Timber");

        Method method = QuickInstallCoordinator.class.getDeclaredMethod(
                "fetcherMatchesPlan",
                UpdateSourceRegistry.UpdateSource.class,
                plan.getClass());
        method.setAccessible(true);

        boolean matches = (boolean) method.invoke(coordinator, source, plan);

        assertTrue(matches);
    }

    @Test
    void removeConflictingSourcesUnregistersMismatchedFetcher() throws Exception {
        QuickInstallCoordinator coordinator = newCoordinator(false);
        UpdateSourceRegistry registry = new UpdateSourceRegistry(Logger.getLogger("test"), new YamlConfiguration());

        CopyOnWriteArrayList<UpdateSourceRegistry.UpdateSource> sources = new CopyOnWriteArrayList<>();
        UpdateSourceRegistry.UpdateSource conflict = new UpdateSourceRegistry.UpdateSource(
                "timber",
                new StubCurseforgeFetcher(),
                TargetDirectory.PLUGINS,
                "Timber.jar",
                "Timber");
        sources.add(conflict);

        setField(registry, "sources", sources);
        setField(coordinator, "updateSourceRegistry", registry);

        Method method = QuickInstallCoordinator.class.getDeclaredMethod(
                "removeConflictingSources",
                CommandSender.class,
                List.class);
        method.setAccessible(true);

        method.invoke(coordinator, null, List.of(conflict));

        assertTrue(registry.getSources().isEmpty());
    }

    private QuickInstallCoordinator newCoordinator(boolean ignoreCompatibilityWarnings) throws Exception {
        Unsafe unsafe = getUnsafe();
        QuickInstallCoordinator coordinator = (QuickInstallCoordinator) unsafe.allocateInstance(QuickInstallCoordinator.class);
        setField(coordinator, "configuration", new YamlConfiguration());
        setField(coordinator, "ignoreCompatibilityWarnings", ignoreCompatibilityWarnings);
        setField(coordinator, "logger", Logger.getLogger("QuickInstallCoordinatorTest"));
        return coordinator;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private Object newInstallationPlan(QuickInstallCoordinator coordinator,
                                       String fetcherType,
                                       String sourceName,
                                       String suggestedName) throws Exception {
        Class<?> planClass = null;
        for (Class<?> inner : QuickInstallCoordinator.class.getDeclaredClasses()) {
            if ("InstallationPlan".equals(inner.getSimpleName())) {
                planClass = inner;
                break;
            }
        }
        if (planClass == null) {
            throw new IllegalStateException("InstallationPlan class not found");
        }

        Constructor<?> constructor = null;
        for (Constructor<?> candidate : planClass.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 10) {
                constructor = candidate;
                constructor.setAccessible(true);
                break;
            }
        }
        if (constructor == null) {
            throw new IllegalStateException("InstallationPlan constructor not found");
        }

        Map<String, Object> options = new LinkedHashMap<>();
        Object plan = constructor.newInstance(
                coordinator,
                "https://example.com",
                "Provider",
                "host",
                fetcherType,
                sourceName,
                suggestedName,
                TargetDirectory.PLUGINS,
                options,
                suggestedName + ".jar");

        Method setFilename = planClass.getDeclaredMethod("setFilename", String.class);
        setFilename.setAccessible(true);
        setFilename.invoke(plan, suggestedName + ".jar");

        return plan;
    }

    private static class StubSpigotFetcher implements UpdateFetcher {
        @Override
        public void loadLatestBuildInfo() {
        }

        @Override
        public String getLatestVersion() {
            return null;
        }

        @Override
        public int getLatestBuild() {
            return 0;
        }

        @Override
        public String getLatestDownloadUrl() {
            return null;
        }

        @Override
        public String getInstalledVersion() {
            return null;
        }
    }

    private static class StubCurseforgeFetcher extends StubSpigotFetcher {
    }
}
