package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LrcParser;
import dev.t1m3.qplayer.lyric.LyricLine;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricRendererTest {

    @Test
    public void repeatedPlainLrcPreparationDoesNotBecomePerSyllable() {
        List<LyricLine> cached = LrcParser.parse(
                "[00:01.00]这是普通歌词\n[00:05.00]这是下一行");

        LyricRenderer.PreparedLyrics first = LyricRenderer.prepareLyrics(cached, false);
        assertFalse(first.animatablePerToken);
        assertTrue(first.lines.get(0).syllables.size() > 1); // private wrap tokens
        assertEquals(1, cached.get(0).syllables.size());    // cache stays pristine

        LyricRenderer.PreparedLyrics second = LyricRenderer.prepareLyrics(cached, false);
        assertFalse(second.animatablePerToken);
        assertEquals(1, cached.get(0).syllables.size());
    }

    @Test
    public void plainLrcLinearSettingStillEnablesSyntheticSweep() {
        List<LyricLine> cached = LrcParser.parse(
                "[00:01.00]这是普通歌词\n[00:05.00]这是下一行");

        LyricRenderer.PreparedLyrics prepared = LyricRenderer.prepareLyrics(cached, true);

        assertTrue(prepared.animatablePerToken);
        assertTrue(prepared.lines.get(0).syllables.size() > 1);
        assertEquals(1, cached.get(0).syllables.size());
    }

    @Test
    public void splitLatinSyllablesFormOneDisplayWordButKeepBothAnimationSegments() {
        // UTF-16 offsets for timed syllables "en" + "dure".
        int[][] words = LyricRenderer.displayWordSyllableRanges(
                "endure forever", new int[]{0, 2, 6, 7, 14});

        assertEquals(2, words.length);
        assertEquals(0, words[0][0]);
        assertEquals(6, words[0][1]);
        assertEquals(0, words[0][2]);
        assertEquals(1, words[0][3]);
    }

    @Test
    public void everyVisibleWordGetsItsOwnGlowGroup() {
        int[][] words = LyricRenderer.displayWordRanges("hold on, 再见");

        assertEquals(4, words.length); // hold, on, 再, 见
        assertEquals("hold", "hold on, 再见".substring(words[0][0], words[0][1]));
        assertEquals("on", "hold on, 再见".substring(words[1][0], words[1][1]));
    }
}
