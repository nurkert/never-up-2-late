package eu.nurkert.neverUp2Late.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
}
