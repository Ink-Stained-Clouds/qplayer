package dev.t1m3.qplayer.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsCatalogTest {

    @Test
    public void systemTitleBarIsDesktopOnlyAndDefaultsOff() {
        SettingSpec spec = setting("windowDecorated");

        assertEquals(Boolean.FALSE, spec.def);
        assertTrue(spec.appliesTo(SettingsCatalog.DESKTOP));
        assertFalse(spec.appliesTo(SettingsCatalog.ANDROID));
    }

    @Test
    public void lyricSizingUsesDottedFixedStepSliders() {
        SettingSpec fontSize = setting("lyricFontSize");
        SettingSpec lineSpacing = setting("lyricLineSpacing");

        assertEquals(SettingSpec.SLIDER, fontSize.type);
        assertTrue(fontSize.dots);
        assertEquals(1, fontSize.step);

        assertEquals(SettingSpec.SLIDER, lineSpacing.type);
        assertTrue(lineSpacing.dots);
        assertEquals(5, lineSpacing.step);
    }

    @Test
    public void maximumCacheUsesPlainSlider() {
        SettingSpec cache = setting("maxCacheSizeMB");

        assertEquals(SettingSpec.SLIDER, cache.type);
        assertFalse(cache.dots);
        assertEquals(50, cache.min);
        assertEquals(1024, cache.max);
    }

    @Test
    public void pageTransitionDefaultsToZoomAndOffersAccessibleFallback() {
        SettingSpec transition = setting(SettingsCatalog.PAGE_TRANSITION_KEY);

        assertEquals(SettingSpec.DROPDOWN, transition.type);
        assertEquals(SettingsCatalog.PAGE_TRANSITION_ZOOM, transition.def);
        assertEquals("Zoom In / Out", transition.options.get(0));
        assertTrue(transition.options.contains("无动画"));
        assertTrue(transition.appliesTo(SettingsCatalog.DESKTOP));
        assertTrue(transition.appliesTo(SettingsCatalog.ANDROID));
    }

    private static SettingSpec setting(String key) {
        return SettingsCatalog.specs().stream()
                .filter(candidate -> key.equals(candidate.key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " setting missing"));
    }
}
