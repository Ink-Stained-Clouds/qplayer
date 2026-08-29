package dev.t1m3.qplayer.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsCatalogTest {

    @Test
    public void systemTitleBarIsDesktopOnlyAndDefaultsOff() {
        SettingSpec spec = SettingsCatalog.specs().stream()
                .filter(candidate -> "windowDecorated".equals(candidate.key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("windowDecorated setting missing"));

        assertEquals(Boolean.FALSE, spec.def);
        assertTrue(spec.appliesTo(SettingsCatalog.DESKTOP));
        assertFalse(spec.appliesTo(SettingsCatalog.ANDROID));
    }
}
