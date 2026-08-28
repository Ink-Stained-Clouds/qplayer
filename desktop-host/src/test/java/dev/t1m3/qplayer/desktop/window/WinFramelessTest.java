package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WinFramelessTest {

    @Test
    public void lyricTopControlsRemainClickableAndEmptySpaceDrags() {
        double width = 1100;

        assertTrue(WinFrameless.isLyricButton(20, 20, width, 1));
        assertTrue(WinFrameless.isLyricButton(width - 20, 20, width, 1));
        assertTrue(WinFrameless.isLyricButton(width - 70, 20, width, 1));

        assertFalse(WinFrameless.isLyricButton(width / 2, 20, width, 1));
        assertFalse(WinFrameless.isLyricButton(20, 3, width, 1));
        assertFalse(WinFrameless.isLyricButton(width - 49, 20, width, 1));
    }

    @Test
    public void lyricTopControlHitBoxesScaleWithDpi() {
        double scale = 1.5;
        double width = 1650;

        assertTrue(WinFrameless.isLyricButton(30, 30, width, scale));
        assertTrue(WinFrameless.isLyricButton(width - 105, 30, width, scale));
        assertFalse(WinFrameless.isLyricButton(width / 2, 30, width, scale));
    }
}
