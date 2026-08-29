package dev.t1m3.qplayer.desktop.settings;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DesktopThemeMonitorTest {

    @Test
    public void windowsAppsThemeUsesZeroForDark() {
        assertTrue(DesktopThemeMonitor.windowsAppsUseLightThemeIsDark(0));
        assertFalse(DesktopThemeMonitor.windowsAppsUseLightThemeIsDark(1));
    }

    @Test
    public void desktopThemeNamesCoverMacGnomeGtkAndKde() {
        assertTrue(DesktopThemeMonitor.themeTextIsDark("Dark"));
        assertTrue(DesktopThemeMonitor.themeTextIsDark("'prefer-dark'"));
        assertTrue(DesktopThemeMonitor.themeTextIsDark("Adwaita-dark"));
        assertTrue(DesktopThemeMonitor.themeTextIsDark("BreezeDark"));
        assertFalse(DesktopThemeMonitor.themeTextIsDark("'default'"));
        assertFalse(DesktopThemeMonitor.themeTextIsDark("BreezeLight"));
    }

    @Test
    public void explicitLightPreferenceDoesNotFallThroughToThemeName() {
        assertTrue(DesktopThemeMonitor.themeTextIsExplicitLight("'prefer-light'"));
        assertFalse(DesktopThemeMonitor.themeTextIsExplicitLight("'default'"));
    }
}
