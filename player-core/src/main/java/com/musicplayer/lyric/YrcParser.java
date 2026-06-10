package com.musicplayer.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetEase Cloud Music YRC parser. Each line is:
 *
 * <pre>{@code
 * [lineStartMs,lineDurationMs](sylStart,sylDur,wordFlag)字(sylStart,sylDur,wordFlag)字...
 * }</pre>
 *
 * Unlike LYS/QRC where the timing tuple follows the syllable text, YRC puts
 * each {@code (start,dur,flag)} tuple <i>before</i> the syllable it applies
 * to. The trailing {@code wordFlag} is currently unused (0 in all observed
 * AMLL exports), but parsed so the regex matches.
 *
 * <p>YRC has no vocal-channel encoding; all lines map to MAIN.
 */
public final class YrcParser {

    private static final Pattern LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)]");
    /** (start,dur,flag) followed by greedy text up to the next '(' or end. */
    private static final Pattern SYLLABLE = Pattern.compile("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)");

    private YrcParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher header = LINE_HEADER.matcher(line);
            if (!header.find()) continue;

            LyricLine ll = new LyricLine();
            Matcher m = SYLLABLE.matcher(line);
            m.region(header.end(), line.length());
            while (m.find()) {
                long start = Long.parseLong(m.group(1));
                long dur = Long.parseLong(m.group(2));
                String text = m.group(4);
                if (text == null) text = "";
                ll.syllables.add(new Syllable(text, start, dur));
            }
            if (!ll.syllables.isEmpty()) out.add(ll);
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }
}
