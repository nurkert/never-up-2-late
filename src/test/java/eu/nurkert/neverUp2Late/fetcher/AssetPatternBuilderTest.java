package eu.nurkert.neverUp2Late.fetcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AssetPatternBuilderTest {

    @Test
    void buildsRegexThatKeepsStaticPartsAndReplacesVersions() {
        String pattern = AssetPatternBuilder.build("DynamicLights-Paper-1.20.6.jar");

        assertTrue(pattern.startsWith("(?i)"));
        Pattern compiled = Pattern.compile(pattern);
        assertTrue(compiled.matcher("DynamicLights-Paper-1.21.0.jar").matches());
        assertFalse(compiled.matcher("DynamicLights-Folia-1.21.0.jar").matches());
    }

    @Test
    void keepsArchiveExtension() {
        String pattern = AssetPatternBuilder.build("demo-1.4.0.zip");
        Pattern compiled = Pattern.compile(pattern);
        assertTrue(compiled.matcher("demo-1.5.0.zip").matches());
        assertFalse(compiled.matcher("demo-1.5.0.jar").matches());
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> AssetPatternBuilder.build("   "));
    }

    @Test
    void keepsSelectedVariantDistinctFromOtherAssets() {
        String selected = "DynamicLights-1.3.0-FOR-MC1.20.jar";
        String pattern = AssetPatternBuilder.build(selected,
                List.of(selected, "DynamicLights-1.3.0-FOR-MC1.19.jar"));

        Pattern compiled = Pattern.compile(pattern);
        assertTrue(compiled.matcher("DynamicLights-1.4.0-FOR-MC1.20.jar").matches());
        assertFalse(compiled.matcher("DynamicLights-1.4.0-FOR-MC1.19.jar").matches());
    }
}
