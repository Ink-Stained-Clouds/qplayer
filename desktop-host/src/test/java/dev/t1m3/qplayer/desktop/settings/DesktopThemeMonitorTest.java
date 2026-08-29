package dev.t1m3.qplayer.desktop.settings;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    @Test
    public void portalColorSchemeUsesTheStandardValues() {
        assertTrue(DesktopThemeMonitor.portalColorSchemeIsDark(1));
        assertFalse(DesktopThemeMonitor.portalColorSchemeIsDark(2));
        assertNull(DesktopThemeMonitor.portalColorSchemeIsDark(0));
        assertNull(DesktopThemeMonitor.portalColorSchemeIsDark(99));
    }

    @Test
    public void kdeBackgroundColorDoesNotDependOnSchemeName() {
        assertTrue(DesktopThemeMonitor.rgbTextIsDark("18,19,28"));
        assertFalse(DesktopThemeMonitor.rgbTextIsDark("239,240,241"));
        assertNull(DesktopThemeMonitor.rgbTextIsDark("not-a-color"));
        assertNull(DesktopThemeMonitor.rgbTextIsDark("999,0,0"));
    }
}
