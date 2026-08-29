package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DesktopLyricChromeMotionTest {

    @Test
    public void canvasMotionProducesIntermediateFramesAtSixtyHertz() {
        DesktopLyricChromeMotion motion = new DesktopLyricChromeMotion();
        motion.update(false, 0L);
        motion.update(true, 1L);

        Set<Integer> visibleSteps = new HashSet<>();
        for (int frame = 1; frame <= 9; frame++) {
            float opacity = motion.update(true, frame * 16_666_667L).opacity();
            visibleSteps.add(Math.round(opacity * 1000f));
        }

        assertTrue("motion must not collapse to two endpoint frames", visibleSteps.size() >= 7);
    }

    @Test
    public void expansionIsBalancedInLogicalPixelsAndReversible() {
        DesktopLyricChromeMotion motion = new DesktopLyricChromeMotion();
        DesktopLyricChromeMotion.Frame hidden = motion.update(false, 0L);
        assertEquals(18f, (hidden.scaleX() - 1f) * DesktopLyricWindow.WIDTH * 0.5f, 0.01f);
        assertEquals(18f, (hidden.scaleY() - 1f) * DesktopLyricWindow.HEIGHT * 0.5f, 0.01f);

        motion.update(true, 1L);
        DesktopLyricChromeMotion.Frame shown = motion.update(true,
                DesktopLyricChromeMotion.DURATION_NANOS + 1L);
        assertEquals(1f, shown.opacity(), 0.0001f);
        assertEquals(1f, shown.scaleX(), 0.0001f);
        assertEquals(1f, shown.scaleY(), 0.0001f);
    }
}
