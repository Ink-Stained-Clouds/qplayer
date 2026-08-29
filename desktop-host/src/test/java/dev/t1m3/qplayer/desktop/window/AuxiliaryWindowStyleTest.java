package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuxiliaryWindowStyleTest {

    @Test
    public void windowsLyricsUseToolStyleWithoutAppWindowStyle() {
        int unrelated = 0x00080000;
        int current = unrelated | AuxiliaryWindowStyle.WS_EX_APPWINDOW;

        int result = AuxiliaryWindowStyle.windowsToolStyle(current);

        assertEquals(AuxiliaryWindowStyle.WS_EX_TOOLWINDOW,
                result & AuxiliaryWindowStyle.WS_EX_TOOLWINDOW);
        assertEquals(0, result & AuxiliaryWindowStyle.WS_EX_APPWINDOW);
        assertEquals(unrelated, result & unrelated);
    }
}
