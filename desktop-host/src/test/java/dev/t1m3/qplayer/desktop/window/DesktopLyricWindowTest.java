package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DesktopLyricWindowTest {

    @Test
    public void onlyThePersistentLockButtonIsAnUnlockRegion() {
        assertTrue(DesktopLyricWindow.isUnlockPoint(870, 146));
        assertFalse(DesktopLyricWindow.isUnlockPoint(72, 146));
        assertFalse(DesktopLyricWindow.isUnlockPoint(870, 34));
        assertFalse(DesktopLyricWindow.isUnlockPoint(900, 146));
    }

    @Test
    public void qmlInputRegionsFollowTheFourCornerLayout() {
        assertTrue(DesktopLyricWindow.isControlPoint(32, 34));
        assertTrue(DesktopLyricWindow.isControlPoint(870, 34));
        assertTrue(DesktopLyricWindow.isControlPoint(72, 146));
        assertTrue(DesktopLyricWindow.isControlPoint(870, 146));
        assertFalse(DesktopLyricWindow.isControlPoint(450, 90));
    }
}
