package eu.nurkert.neverUp2Late.command;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import eu.nurkert.neverUp2Late.update.UpdateSourceRegistry.TargetDirectory;

import sun.misc.Unsafe;

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

    private QuickInstallCoordinator newCoordinator(boolean ignoreCompatibilityWarnings) throws Exception {
        Unsafe unsafe = getUnsafe();
        QuickInstallCoordinator coordinator = (QuickInstallCoordinator) unsafe.allocateInstance(QuickInstallCoordinator.class);
        setField(coordinator, "configuration", new YamlConfiguration());
        setField(coordinator, "ignoreCompatibilityWarnings", ignoreCompatibilityWarnings);
        setField(coordinator, "logger", Logger.getLogger("QuickInstallCoordinatorTest"));
        return coordinator;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = QuickInstallCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
