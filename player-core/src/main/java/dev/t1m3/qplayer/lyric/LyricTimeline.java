package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, renderer-independent lyric timeline shared by the full lyric page
 * and compact hosts such as desktop lyrics.
 *
 * <p>Parsing stays outside this class. It takes parsed lines, removes empty
 * markers, protects the parser/controller cache by copying each line, derives
 * whether token timing is real, and groups background vocals with the main line
 * they follow. No Skija or qml4j type leaks into the result, so a second render
 * thread can safely retain and query one prepared model.
 */
public final class LyricTimeline {

    private LyricTimeline() {}

    public static Prepared prepare(List<LyricLine> source, boolean linearPlainLrc) {
        List<LyricLine> filtered = new ArrayList<>();
        if (source != null) {
            for (LyricLine line : source) {
                if (line != null && hasVisibleText(line)) filtered.add(copyLine(line));
            }
        }

        boolean perSyllable = false;
        for (LyricLine line : filtered) {
            if (line.syllables.size() > 1) {
                perSyllable = true;
                break;
            }
        }

        if (!perSyllable) {
            for (LyricLine line : filtered) {
                if (line.syllables.size() != 1) continue;
                List<Syllable> tokens = tokenizePlainLine(line.syllables.get(0), linearPlainLrc);
                if (tokens.size() > 1) {
                    line.syllables.clear();
                    line.syllables.addAll(tokens);
                }
            }
        }

        List<LyricLine> lines = Collections.unmodifiableList(filtered);
        List<Group> groups = buildGroups(lines);
        int[] lineToGroup = new int[lines.size()];
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            Group group = groups.get(groupIndex);
            for (int lineIndex = group.from; lineIndex < group.to; lineIndex++) {
                lineToGroup[lineIndex] = groupIndex;
            }
        }
        return new Prepared(lines, groups, lineToGroup,
                perSyllable || linearPlainLrc, perSyllable);
    }

    /** Last group whose start is not after {@code positionMs}, or {@code -1}. */
    public static int activeGroupIndex(List<Group> groups, long positionMs) {
        int low = 0;
        int high = groups != null ? groups.size() - 1 : -1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (groups.get(mid).startMs <= positionMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    /**
     * Compact representation at a playback position. Background-vocal lines are
     * included in the active text block; the next main group is supplied for
     * compact renderers that want a subdued preview.
     */
    public static Frame frameAt(Prepared prepared, long positionMs) {
        if (prepared == null || prepared.groups.isEmpty()) return Frame.EMPTY;
        int index = activeGroupIndex(prepared.groups, positionMs);
        if (index < 0) return new Frame("", "", textOf(prepared, 0), 0f, -1);
        Group group = prepared.groups.get(index);
        String current = textOf(prepared, index);
        String translation = firstSidecar(prepared.lines, group, true);
        String next = index + 1 < prepared.groups.size() ? textOf(prepared, index + 1) : "";
        long span = Math.max(1L, group.endMs - group.startMs);
        float progress = Math.max(0f, Math.min(1f, (positionMs - group.startMs) / (float) span));
        return new Frame(current, translation, next, progress, index);
    }

    private static String textOf(Prepared prepared, int groupIndex) {
        Group group = prepared.groups.get(groupIndex);
        StringBuilder text = new StringBuilder();
        for (int i = group.from; i < group.to; i++) {
            String value = prepared.lines.get(i).text().trim();
            if (value.isEmpty()) continue;
            if (text.length() > 0) text.append("  ·  ");
            text.append(value);
        }
        return text.toString();
    }

    private static String firstSidecar(List<LyricLine> lines, Group group, boolean translation) {
        for (int i = group.from; i < group.to; i++) {
            String value = translation ? lines.get(i).translation : lines.get(i).romaji;
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static boolean hasVisibleText(LyricLine line) {
        for (Syllable syllable : line.syllables) {
            if (syllable != null && syllable.text != null && !syllable.text.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static LyricLine copyLine(LyricLine source) {
        LyricLine copy = new LyricLine();
        copy.syllables.addAll(source.syllables); // Syllable is immutable.
        copy.vocalChannel = source.vocalChannel;
        copy.translation = source.translation;
        copy.romaji = source.romaji;
        return copy;
    }

    private static List<Syllable> tokenizePlainLine(Syllable syllable, boolean spreadEvenly) {
        String text = syllable.text == null ? "" : syllable.text;
        List<String> runs = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            char c = text.charAt(offset);
            if (Character.isWhitespace(c)) {
                int end = offset + 1;
                while (end < text.length() && Character.isWhitespace(text.charAt(end))) end++;
                runs.add(text.substring(offset, end));
                offset = end;
            } else if (isWrapCjk(c)) {
                runs.add(String.valueOf(c));
                offset++;
            } else {
                int end = offset + 1;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                        && !isWrapCjk(text.charAt(end))) end++;
                runs.add(text.substring(offset, end));
                offset = end;
            }
        }

        List<Syllable> result = new ArrayList<>(runs.size());
        if (!spreadEvenly) {
            for (String run : runs) {
                result.add(new Syllable(run, syllable.startMs, syllable.durationMs));
            }
            return result;
        }
        if (runs.isEmpty()) return result;
        long each = syllable.durationMs / runs.size();
        for (int i = 0; i < runs.size(); i++) {
            long start = syllable.startMs + i * each;
            long duration = i == runs.size() - 1
                    ? syllable.startMs + syllable.durationMs - start : each;
            result.add(new Syllable(runs.get(i), start, duration));
        }
        return result;
    }

    private static boolean isWrapCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7A3);
    }

    public static boolean isBackground(LyricLine.VocalChannel channel) {
        return channel == LyricLine.VocalChannel.BACKGROUND
                || channel == LyricLine.VocalChannel.BACKGROUND_LEFT
                || channel == LyricLine.VocalChannel.BACKGROUND_RIGHT;
    }

    private static List<Group> buildGroups(List<LyricLine> lines) {
        List<Group> result = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            int end = index + 1;
            long endMs = lines.get(index).endMs();
            while (end < lines.size() && isBackground(lines.get(end).vocalChannel)) {
                endMs = Math.max(endMs, lines.get(end).endMs());
                end++;
            }
            result.add(new Group(index, end, lines.get(index).startMs(), endMs));
            index = end;
        }
        return Collections.unmodifiableList(result);
    }

    public static final class Prepared {
        public final List<LyricLine> lines;
        public final List<Group> groups;
        public final int[] lineToGroup;
        public final boolean animatablePerToken;
        public final boolean perSyllableSource;

        private Prepared(List<LyricLine> lines, List<Group> groups, int[] lineToGroup,
                         boolean animatablePerToken, boolean perSyllableSource) {
            this.lines = lines;
            this.groups = groups;
            this.lineToGroup = lineToGroup;
            this.animatablePerToken = animatablePerToken;
            this.perSyllableSource = perSyllableSource;
        }
    }

    public static final class Group {
        public final int from;
        public final int to;
        public final long startMs;
        public final long endMs;

        private Group(int from, int to, long startMs, long endMs) {
            this.from = from;
            this.to = to;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public boolean contains(int lineIndex) {
            return lineIndex >= from && lineIndex < to;
        }
    }

    public static final class Frame {
        private static final Frame EMPTY = new Frame("", "", "", 0f, -1);
        public final String current;
        public final String translation;
        public final String next;
        public final float progress;
        public final int groupIndex;

        private Frame(String current, String translation, String next,
                      float progress, int groupIndex) {
            this.current = current;
            this.translation = translation;
            this.next = next;
            this.progress = progress;
            this.groupIndex = groupIndex;
        }
    }
}
