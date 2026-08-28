package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.LyricTimeline;
import dev.t1m3.qplayer.lyric.Syllable;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.skija.TextLine;
import io.github.humbleui.skija.impl.Managed;
import io.github.humbleui.skija.impl.Native;
import io.github.humbleui.skija.impl.RefCnt;
import io.github.humbleui.skija.shaper.Shaper;
import io.github.humbleui.types.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apple Music-style lyric column. Lines are left-anchored at {@code leftX}
 * and wrap into a column of width {@code columnWidth}. The active line is
 * vertically centered in the visible area; surrounding lines flow above/
 * below in a dimmer style. Each visual row is shaped once with HarfBuzz and
 * cached as a TextBlob; a runtime shader applies independently timed syllable
 * lift without splitting that shaped row back into draw calls.
 *
 * <p>The layout/shaping pass is cached: each line is broken at syllable
 * boundaries when its width exceeds the column, and every wrapped sub-row
 * counts toward the line's total height. Scroll is driven by cumulative
 * {@code lineTops}, not a fixed per-line spacing, so wrapped lines push later
 * lines down without overlapping while playback frames reuse native blobs.
 */
public class LyricRenderer {

    /**
     * Row-height multiplier applied to the configured font size. 1.18×
     * is right at the typical sans-serif ascent+descent envelope — any
     * tighter and capital letters from adjacent rows start to touch.
     */
    private static final float ROW_HEIGHT_RATIO = 1.55f;
    /**
     * Tighter ratio for continuation sub-rows of a wrapped line. The
     * first sub-row still uses full {@link #ROW_HEIGHT_RATIO} so line-
     * to-line separation is unchanged; continuation rows use 1.0× so the
     * second half of a long lyric hugs the first.
     */
    private static final float WRAPPED_ROW_HEIGHT_RATIO = 1.0f;
    /**
     * Sub-line (translation / romaji) advance, relative to its own font size.
     */
    private static final float SUB_ROW_HEIGHT_RATIO = 1.1f;
    // Small extra gap above the translation/romaji block when the main lyric
    // wrapped. Kept well under one wrap-row height — too large (≈ a row) reads
    // as a blank line between the lyric and its translation.
    private static final float WRAP_SUB_GAP = 4f;

    /**
     * How many lines above/below the active line to actually draw.
     */
    private static final int VISIBLE_RADIUS = 16;
    /**
     * Minimum gap (ms) between two groups to insert an interlude dot row.
     * AMLL's reference uses 4000 ms, but that filters out most of the
     * short verse-to-verse pauses our user-tested songs actually have.
     * We use 2000 ms + proportional phase scaling in
     * {@link #renderInterludeDots} so short gaps still show dots with
     * fade-in/hold/exit windows scaled down to fit.
     */
    private static final long INTERLUDE_THRESHOLD_MS = 2000L;
    /**
     * AMLL trims the effective interlude end by 250 ms so the next
     * line has room to scroll in before it actually starts singing.
     */
    private static final long INTERLUDE_TRAIL_TRIM_MS = 250L;
    /**
     * Layout height (px) reserved for the inline interlude dot row. The
     * dots scroll into the centre position like a real lyric line.
     */
    private static final float INTERLUDE_DOTS_ROW_H = 40f;
    /**
     * Radius (px) of each interlude dot. Slot height + dot radius +
     * spacing all scale together to keep the dots visually balanced
     * inside their reserved row.
     */
    private static final float INTERLUDE_DOT_RADIUS = 6.8f;
    /**
     * Centre-to-centre horizontal spacing between dots.
     */
    private static final float INTERLUDE_DOT_SPACING = 27f;
    /**
     * Peak lift amplitude in pixels (negative = upward). AMLL's
     * reference value is 0.05em ≈ 1.5 px at 30 px font. Larger values
     * break the wave illusion: with 4 px+ amplitude the height step
     * between adjacent syllables becomes individually visible and the
     * effect reads as "each word kicks independently" instead of "a
     * smooth wave flows through the line". Match AMLL exactly so
     * multiple in-flight syllables blend continuously.
     */
    private static final float LIFT_PEAK_PX = 2.0f; // Apple Specs.syllableLift = 2.0
    /**
     * Per-syllable lift duration floor. AMLL uses {@code max(1000ms, wordDur)}
     * for {@code initFloatAnimation}: each syllable's translateY animation
     * runs for at least a full second. With typical pop pacing (~250 ms
     * per syllable) that means 3-4 syllables are simultaneously mid-rise
     * at any given moment, and the per-字 lifts visually fuse into a
     * continuous wave that flows along the line — already-sung words
     * are still rising, currently-sung words rise faster, upcoming words
     * are at 0 until their own delay elapses.
     *
     * <p>We used to clamp this to 200 ms to make per-字 progress
     * "visible" within a single syllable, but that killed the wave
     * overlap that gives AMLL its signature trailing-lift feel.
     */
    private static final long LIFT_MIN_DURATION_MS = 1000L;
    /**
     * Width of the gradient mask sweep band, in pixels — the lit→unlit feather that
     * rides the sweep head across the active line. The head itself tracks the play
     * head (no easing), so a wide feather is the ONLY thing that makes the per-字
     * reveal read as slow: each glyph lingers half-lit while the whole band crosses
     * it. AMLL/Apple use ~40 (feather 40.0); 16 keeps a soft edge (no aliasing) while
     * lighting each character crisply as the head passes.
     */
    private static final float SWEEP_FADE_PX = 16f;
    /**
     * Mask alpha on the unlit side of the active line's sweep. Multiplies the
     * line's baseAlpha at composite, so the active line's not-yet-sung text is
     * {@code activeBase * DARK_MASK_ALPHA}. Kept ≥ the deselected idle alpha
     * (0.30 main / 0.24 BG) so a line BRIGHTENS as it activates instead of first
     * dipping below the surrounding lines and then lighting up. Deselected lines
     * are dimmed to keep a strong sung/unsung sweep contrast on the active line.
     */
    private static final float DARK_MASK_ALPHA = 0.36f;
    /**
     * Duration of the active → idle handoff.
     */
    private static final long ACTIVE_FADE_OUT_MS = 350L;
    /** Keep the completed line fully active briefly before beginning its exit. */
    private static final long ACTIVE_FADE_OUT_DELAY_MS = 100L;
    /**
     * Mirror of {@link #ACTIVE_FADE_OUT_MS} for the lead-in. Starting
     * activeK from 0 at {@code startMs} would snap the line's alpha
     * (idle 0.42 → active × dark-mask 0.2) and read as a sudden dim;
     * letting it rise over 600ms around {@code startMs} gives the
     * baseAlpha (and the mask's dark alpha) time to crossfade smoothly
     * up to playback levels.
     */
    private static final long ACTIVE_FADE_IN_MS = 600L;
    /**
     * Delay the next-line handoff without shortening its fade. The fade and
     * scroll switch begin 450ms before the timestamp and finish 150ms after it.
     */
    private static final long ACTIVE_FADE_IN_DELAY_MS = 150L;
    /**
     * BG line scale at rest (idle). 0 means "fully invisible until the
     * group activates" — BG grows out from the main line's bottom corner
     * on enter and collapses back to nothing on exit.
     */
    private static final float BG_SCALE_IDLE = 0f;
    /**
     * Skip drawing the BG layer below this activeK to avoid scale(0) artefacts.
     */
    private static final float BG_VISIBLE_THRESHOLD = 0.001f;
    /**
     * Snappy pop-in window for the BG scale animation, in ms. Distinct
     * from the group's {@link #ACTIVE_FADE_IN_MS} so the BG can shoot
     * out fast (with overshoot) while the row's alpha still crossfades
     * at its calmer pace.
     */
    private static final long BG_POP_IN_MS = 460L;
    /**
     * Small lead so the BG row trails the main line a touch before popping in.
     */
    private static final long BG_POP_IN_DELAY_MS = 150L;
    /**
     * Pop-out (collapse) window. Slightly longer so the shrink reads as deliberate.
     */
    private static final long BG_POP_OUT_MS = 280L;
    /**
     * Pull the BG anchor this many pixels above the main+sub block bottom
     * so the BG content reads as "tucked into" the main line rather than
     * floating below it. With this set the BG's ascender region slightly
     * overlaps the trailing edge of the main's last sub-line, which fits
     * the "塞进 line 之间的缝隙" feedback.
     */
    private static final float BG_HUG_OFFSET_PX = 10f;
    /**
     * Vertical position of the active group's centre as a fraction of the
     * lyric column height. AMLL uses 0.35 by default (active sits above
     * geometric centre, so upcoming lines have more room below). 0.5
     * would centre exactly; 0.35 matches the reference player layout.
     */
    private static final float ALIGN_POSITION = 0.35f;
    /** Keep the first row of an unusually tall active group inside the lyric column. */
    private static final float ACTIVE_GROUP_TOP_MARGIN_PX = 12f;
    // Breathing room (fraction of the column) left beyond the first / last line at the
    // scroll extremes: a touch over half the column so the ends have a generous run-out
    // (and the auto-follow keeps centring lines naturally rather than pinning at edges).
    private static final float SCROLL_EDGE_PAD = 0.7f;

    // ---- Depth scaling (Apple Specs) -------------------------------------
    // Inactive lines render at deselectedTransform (0.97×); the active group
    // grows to emphasizingScaleRange's upper bound (1.14×). Interpolated by
    // activeK so the scale crossfades with the highlight rather than snapping.
    private static final float DESELECTED_SCALE = 0.97f;
    private static final float EMPHASIS_SCALE = 1.14f;

    // ---- Scroll spring tunings (ported from AMLL computeLinePosYSpringParams) --
    // Keep the established per-line cascade renderer, but use a gently
    // underdamped spring. ζ≈0.68 keeps one visible overshoot while damping
    // the repeated oscillation that becomes conspicuous across rapid line changes.
    // k=65 preserves the established response speed; only the settling is calmer.
    private static final double SCROLL_STIFFNESS_MIN = 65.0;
    private static final double SCROLL_STIFFNESS_MAX = 65.0;
    private static final double SCROLL_INTERVAL_MIN_MS = 100.0;
    private static final double SCROLL_INTERVAL_MAX_MS = 800.0;
    private static final double SCROLL_DAMPING_MULT = 1.365; // damping ≈ 11.0 @ k=65, ζ≈0.68
    // Steadier fixed spring while seeking or during an interlude.
    private static final double SCROLL_STIFFNESS_INTERLUDE = 55.0;
    private static final double SCROLL_DAMPING_INTERLUDE = 10.1;
    // Non-spring fallback uses the same current k/damping pair, without cascade.
    private static final double SCROLL_STIFFNESS_FIRM = 65.0;
    private static final double SCROLL_DAMPING_FIRM = 11.0;
    // The original rigid seek spring: slightly overdamped, so the whole column
    // glides to the new position without the newer fixed-duration tween or bounce.
    private static final double SEEK_SPRING_STIFFNESS = 180.0;
    private static final double SEEK_SPRING_DAMPING = 28.0;
    /** Duration of render-resume/unclassified-jump scrolling; quartic ease-out. */
    private static final long DISCONTINUITY_EASE_DURATION_NS = 500_000_000L;
    // Apple liftSpring: mass 1, stiffness 14, damping 7 → ω0=√14, ζ≈0.935.
    private static final double LIFT_OMEGA0 = 3.7416574; // sqrt(14)
    private static final double LIFT_ZETA = 0.935414;    // 7 / (2·√14)
    // Peak opacity of the white glow behind a sustained timed display word.
    private static final float GLOW_ALPHA = 0.55f;
    // Only gates the glow/ribbon-lift when dropShadow is on (see shadowOn plumbing
    // below) -- with the shadow off, every display word glows regardless of how
    // long it's held.
    private static final long WORD_GLOW_MIN_DURATION_MS = 1500L;
    private static final float WORD_RIBBON_LIFT_PX = 2f;
    private static final float MAX_SHADER_LIFT_PX = LIFT_PEAK_PX + WORD_RIBBON_LIFT_PX;
    private static final float TEXT_SHADOW_OFFSET_Y = 2f;
    private static final float TEXT_SHADOW_ALPHA = 0.48f;
    /** Fixed SkSL uniform capacity. A wrapped visual row is normally below 30
     * timed tokens; 128 leaves ample room for pathological no-break lyrics while
     * keeping the per-frame upload small (2 KiB). */
    private static final int MAX_LIFT_SEGMENTS = 128;
    private static final int MAX_WORD_LIFT_SEGMENTS = 32;
    private static final float TEXT_SUPERSAMPLE = 2f;
    private static final float TEXT_RASTER_PAD = 8f;
    private static final int MAX_HIGH_RES_ROWS = 12;
    private static final String LIFT_SHADER_RESOURCE = "/shaders/lyric/syllable_lift.sksl";
    // Per-line scroll cascade (Apple Specs.lineDelay = 0.05). The active line and
    // everything ABOVE it move together (delay 0) — lockstep preserves their
    // spacing so the active line never rises into a still-stationary line above it
    // (the overlap) and never stalls before moving (the hitch). Only the lines
    // BELOW the active line trail, with a shrinking step, for a downward wave.
    private static final double LINE_DELAY_S = 0.05;
    private static final double LINE_DELAY_DECAY = 1.05;
    // A seek that moves the anchor more than this many lines snaps the whole column
    // instead of spring-scrolling: a long spring would animate the lines that
    // happen to overlap the old window while the freshly-revealed lines just appear,
    // a jarring half-animate/half-flash mix. Small jumps still spring smoothly.
    private static final int SNAP_JUMP_LINES = 6;

    private List<LyricLine> lines = Collections.emptyList();
    /** Whether every line in the current song has usable monotonic per-token
     *  timing to sweep/lift/glow with — real for per-syllable sources, or
     *  synthetic-but-evenly-spread for plain LRC when
     *  {@link LyricConfig#linearAnimForPlainLrc} is on. False only for plain
     *  LRC with that setting off, where lines light up as one block instead.
     *  Computed once at {@link #setLyrics}, not per-frame, since it also
     *  decided how the lines were tokenized. */
    private boolean animatablePerToken = false;
    /** True only when the source itself carries real per-word/per-syllable timing.
     * Synthetic timing generated for plain LRC must never enable word glow. */
    private boolean wordGlowSupported = false;
    /**
     * Lines bundled into "active groups". A solo line is its own group; a
     * pair (or chain) of overlapping DUET_LEFT / DUET_RIGHT lines becomes
     * a single group. Used so the active highlight + scroll target stick
     * to the whole duet block until the last voice finishes — without
     * this, the moment the second singer starts mid-phrase the first
     * singer's row would flip to "non-active" and freeze its sweep.
     */
    private List<LyricTimeline.Group> groups = Collections.emptyList();
    /**
     * Index into {@link #groups} per line. Sized to lines.size().
     */
    private int[] lineToGroup = new int[0];
    private int activeGroupIndex = -1;
    // Screen-space [top, bottom] spanned by the currently-lit lines in the last
    // render, accumulated from their actual drawn positions (so it tracks the spring
    // animation, BG pop-out and user scroll exactly). The edge-blur compositor reads
    // this to keep every lit line inside the sharp band, not just the anchor line.
    private float litBandTop, litBandBottom;
    private boolean litBandValid;
    // Time-smoothed copy of the lit band. A line joins the band when its activeK
    // crosses the 0.5 gate, which snaps the raw bottom down a whole line; easing the
    // exposed bounds toward that target turns the snap into a continuous crossfade of
    // the edge blur. Time-constant (seconds) sets how fast it catches up.
    private static final float LIT_BAND_TAU = 0.14f;
    private float litBandTopSmooth, litBandBottomSmooth;
    private final float[] litBandResult = new float[2];
    private boolean litBandSmoothInit;
    private long litBandSmoothNs;
    /**
     * Spring-driven vertical scroll. Stiffness/damping pair tuned to settle
     * a typical line jump in ~500ms with a barely-visible overshoot,
     * matching Apple Music's lyric flow. Duration-based easing would
     * restart on every line change; the spring carries velocity through.
     */
    // The global fallback uses the same k=65 / damping=11 tuning.
    private final SpringAnim scrollAnim = new SpringAnim(SCROLL_STIFFNESS_FIRM, SCROLL_DAMPING_FIRM);
    private final SpringAnim seekAnim = new SpringAnim(SEEK_SPRING_STIFFNESS, SEEK_SPRING_DAMPING);
    // Last spring-mode flag the scrollAnim was retuned for; -1 = not yet applied.
    private int lastSpringMode = -1;

    // Wrap layout cache. rowStarts (syllable break indices per line) and the
    // per-line heights depend only on (lines, font sizes, weight, column width,
    // sub-line visibility) — NOT on the play head — yet the layout pass recomputed
    // them, and reshaped+reallocated an int[] per line, every single frame. Cache
    // them and rebuild only when an input changes; per frame we recompute just the
    // play-head-dependent interlude slots and cumulative tops (plain arithmetic,
    // reused buffers). Mirrors the engine's "don't recompute invariants per frame".
    private int[][] cachedRowStarts;
    private float[] cachedLineHeights;
    // Wrapped sub-line rows per line (null when absent/hidden), cached with the layout
    // so the per-frame draw never re-splits or allocates.
    private ShapedText[][] cachedRomajiRows;
    private ShapedText[][] cachedTranslationRows;
    /** HarfBuzz output for every final visual row. TextBlob and caret positions are
     * immutable and reused until a layout input changes; playback never reshapes. */
    private ShapedRow[][] cachedShapedRows;
    /** Renderer-owned timing fragments used only when an oversized source token
     * must be split at a Unicode/grapheme boundary to make wrapping possible. */
    private List<List<Syllable>> cachedLayoutSyllables;
    // Per-syllable advances obtained from the full-line HarfBuzz result. These are
    // used only to choose wrap boundaries; actual drawing uses each row's TextBlob.
    private float[][] cachedSylWidths;
    private float[] lineTopsBuf = new float[0];
    private float[] effHeightsBuf = new float[0];
    private float[] interludeBuf = new float[0];
    // Per-line scroll springs (cascade). lineCurTop/lineVelTop track each line's
    // drawn top + velocity; only the visible window is integrated, off-window lines
    // snap to target. Active only when spring physics is on.
    private float[] lineCurTop = new float[0];
    private float[] lineVelTop = new float[0];
    // Per-line cascade delay (seconds) over the visible window. Reused buffer.
    private double[] cascadeDelayBuf = new double[0];
    private boolean lineSpringInit = false;
    private int prevVisStart = 0;
    private int prevVisEnd = 0;
    private int springAnchorPrev = Integer.MIN_VALUE;
    private int renderedAnchorPrev = Integer.MIN_VALUE;
    private int cascadeDir = 1; // +1 advancing (scroll up), -1 seeking back (scroll down)
    /** Discontinuous position changes move the whole column with a non-spring ease-out. */
    private boolean seekEaseNextRender = false;
    private boolean seekEaseActive = false;
    /** Explicit playback seeks use the original rigid global spring. */
    private boolean seekSpringNextRender = false;
    private boolean seekSpringActive = false;
    private long seekEaseStartNs = 0L;
    private float seekEaseFrom = 0f;
    private float seekEaseTo = 0f;
    private long springAnchorChangeNs = 0L;
    private long springLastNs = 0L;

    // --- Manual scroll (drag / fling) ------------------------------------------
    // While the user drags the lyric column its position is hand-controlled; the
    // karaoke highlight keeps following the play head. Releasing flings with engine-
    // style inertia (windowed release velocity + constant deceleration). After an idle
    // period, the next line change eases the column back to the follow position using
    // scrollAnim, while explicit seeks and render resumes use the rigid quartic tween.
    private static final float SCROLL_DECEL = 2400f;     // px/s^2 (fling deceleration)
    private static final float SCROLL_MIN_FLING = 60f;   // px/s below which fling stops
    private static final long SCROLL_IDLE_RETURN_NS = 4_000_000_000L; // 4s idle before auto-return
    private static final int SCROLL_VEL_SAMPLES = 8;
    private static final float SCROLL_VEL_WINDOW = 0.09f; // s of history for release velocity
    private boolean userScrollActive;   // drag, fling, idle-hold, or returning
    private boolean userDragging;       // finger currently down
    private boolean userFling;          // coasting after release
    private boolean userReturning;      // easing back to follow via scrollAnim
    private float userScroll;           // content-space offset (same space as scrollY)
    private float userFlingVel;         // px/s
    private long userScrollLastNs;      // fling integration clock
    private long userLastInteractNs;    // last drag/fling activity (idle timer)
    private int userHoldAnchor = Integer.MIN_VALUE; // active line when interaction stopped
    private int userScrollPrevAnchor = Integer.MIN_VALUE; // detect a seek jump to cancel scroll
    private float lastScrollY;          // last rendered scrollY (seeds a fresh drag)
    private float lastCenterY;          // last centerY (maps a tapped screen y to a line)
    private float scrollMin, scrollMax; // content-space clamp bounds (set each frame)
    private final long[] dragSampleNs = new long[SCROLL_VEL_SAMPLES];
    private final float[] dragSampleY = new float[SCROLL_VEL_SAMPLES];
    private int dragSampleCount;

    // Reused per active line each frame (syllable left edges in screen coordinates).
    private float[] sylLeftBuf = new float[0];
    // Reused saveLayer paint for the active row's composite alpha; was a native
    // Paint allocated per active line per frame. Kept alive for the renderer's
    // lifetime (one line's saveLayer/restore completes before the next), so the
    // "keep alive until restore" constraint below is satisfied without per-call new.
    private final io.github.humbleui.skija.Paint lyricLayerPaint = new io.github.humbleui.skija.Paint();
    /**
     * Reusable paint for interlude dots — avoids per-frame allocation.
     */
    private final io.github.humbleui.skija.Paint dotPaint = new io.github.humbleui.skija.Paint();
    // Reused sweep-mask state. The fixed-band gradient shader is cached and only
    // rebuilt when its dark colour changes (activeK fade); the head is positioned
    // by translating the canvas over a cached oversized rect, so a steady sweep
    // allocates nothing. sweepShaderDark = NaN forces the first build.
    private final io.github.humbleui.skija.Paint sweepPaint = new io.github.humbleui.skija.Paint();
    private final int[] sweepColors = new int[2];
    private final float[] sweepStops = new float[2];
    private Shader sweepShader;
    private float sweepShaderDark = Float.NaN;
    private final Rect sweepBigRect = Rect.makeLTRB(-100000f, -100000f, 100000f, 100000f);
    // White glow behind the currently sustained display word. It is rendered through one
    // blurred layer; completed and not-yet-active words never enter this layer.
    private final io.github.humbleui.skija.Paint glowGlyphPaint = newGlowGlyphPaint();
    private final io.github.humbleui.skija.Paint glowLayerPaint = newGlowLayerPaint();
    private final io.github.humbleui.skija.Paint textShadowPaint = newTextShadowPaint();
    // Per-syllable lift offset for the active row.
    private float[] liftBuf = new float[0];
    private final float[] liftUniformBuf = new float[MAX_LIFT_SEGMENTS * 4];
    private final float[] wordLiftUniformBuf = new float[MAX_WORD_LIFT_SEGMENTS * 4];
    private Shaper harfBuzzShaper;
    private RuntimeEffect liftEffect;
    private RuntimeEffectBuilder liftBuilder;
    private final java.util.ArrayDeque<ShapedRow> highResRowLru = new java.util.ArrayDeque<>();

    private static io.github.humbleui.skija.Paint newGlowGlyphPaint() {
        io.github.humbleui.skija.Paint p = new io.github.humbleui.skija.Paint();
        p.setAntiAlias(true);
        return p;
    }

    private static io.github.humbleui.skija.Paint newGlowLayerPaint() {
        io.github.humbleui.skija.Paint p = new io.github.humbleui.skija.Paint();
        // Apple glowRadius = 5.0; Skia blur sigma ≈ radius * 0.5. One layer blur over
        // the row replaces the old per-glyph mask-filter blur.
        p.setImageFilter(io.github.humbleui.skija.ImageFilter.makeBlur(
                2.5f, 2.5f, io.github.humbleui.skija.FilterTileMode.CLAMP));
        return p;
    }

    private static io.github.humbleui.skija.Paint newTextShadowPaint() {
        io.github.humbleui.skija.Paint p = new io.github.humbleui.skija.Paint();
        p.setAntiAlias(true);
        p.setColor(0xFF000000);
        p.setMaskFilter(io.github.humbleui.skija.MaskFilter.makeBlur(
                io.github.humbleui.skija.FilterBlurMode.NORMAL, 2.2f));
        return p;
    }

    private List<LyricLine> layoutKeyLines;
    private int layoutKeyN;
    private int layoutKeyLyricSize;
    private int layoutKeySubSize;
    private int layoutKeyColW = -1;
    private Fonts.Weight layoutKeyWeight;
    private float layoutKeyRowRatio = -1f;
    private boolean layoutKeyRomaji;
    private boolean layoutKeyTranslation;
    private boolean layoutKeyScale = true;
    private Font layoutKeyLyricFont;
    private Font layoutKeySubFont;
    private Font layoutKeyBgFont;

    /** Cached immutable HarfBuzz row. Syllable coordinates are local to the blob. */
    private static final class ShapedRow implements AutoCloseable {
        final int from;
        final int to;
        final TextBlob blob;
        final float width;
        final float leadingWidth;
        final float[] syllableX;
        final WordSpan[] words;
        Image highResImage;
        Shader highResImageShader;
        float rasterLeft;
        float rasterTop;
        float rasterWidth;
        float rasterHeight;
        boolean rasterWithShadow;

        ShapedRow(int from, int to, TextBlob blob, float width, float leadingWidth,
                  float[] syllableX, WordSpan[] words) {
            this.from = from;
            this.to = to;
            this.blob = blob;
            this.width = width;
            this.leadingWidth = leadingWidth;
            this.syllableX = syllableX;
            this.words = words;
        }

        @Override public void close() {
            closeRaster();
            if (blob != null) blob.close();
        }

        void closeRaster() {
            if (highResImageShader != null) {
                highResImageShader.close();
                highResImageShader = null;
            }
            if (highResImage != null) {
                highResImage.close();
                highResImage = null;
            }
        }
    }

    private static final class ShapedText implements AutoCloseable {
        final TextBlob blob;
        final float width;

        ShapedText(TextBlob blob, float width) {
            this.blob = blob;
            this.width = width;
        }

        @Override public void close() {
            if (blob != null) blob.close();
        }
    }

    /** A display word, independent from its timed syllables. In "en"+"dure"
     * this spans both syllables for whole-word glow/ribbon lift, while the base
     * syllable lift still keeps two independently timed segments. */
    static final class WordSpan {
        final int firstSyllable;
        final int lastSyllable;
        final int utf16Start;
        final int utf16End;
        final float x0;
        final float x1;

        WordSpan(int firstSyllable, int lastSyllable, int utf16Start,
                 int utf16End, float x0, float x1) {
            this.firstSyllable = firstSyllable;
            this.lastSyllable = lastSyllable;
            this.utf16Start = utf16Start;
            this.utf16End = utf16End;
            this.x0 = x0;
            this.x1 = x1;
        }
    }

    private static Fonts.Weight toFontsWeight(LyricConfig.FontWeight w) {
        switch (w) {
            case THIN:
                return Fonts.Weight.THIN;
            case LIGHT:
                return Fonts.Weight.LIGHT;
            case MEDIUM:
                return Fonts.Weight.MEDIUM;
            default:
                return Fonts.Weight.REGULAR;
        }
    }

    /**
     * Renderer has at least one parsed lyric line — used by the view
     * layer to decide whether to draw the timeline or a "no lyrics" hint.
     */
    public boolean hasLines() {
        return !lines.isEmpty();
    }

    /** Release the renderer's reusable native Skia objects with its owning scene. */
    public void dispose() {
        clearLayoutCache();
        if (sweepShader != null) {
            sweepShader.close();
            sweepShader = null;
        }
        if (harfBuzzShaper != null) {
            harfBuzzShaper.close();
            harfBuzzShaper = null;
        }
        if (liftBuilder != null) {
            liftBuilder.close();
            liftBuilder = null;
        }
        if (liftEffect != null) {
            liftEffect.close();
            liftEffect = null;
        }
        lyricLayerPaint.close();
        dotPaint.close();
        sweepPaint.close();
        glowGlyphPaint.close();
        glowLayerPaint.close();
        textShadowPaint.close();
    }

    /** Route the next playback-position change through the original rigid seek spring. */
    public void easeSeekOnNextRender() {
        cancelUserScrollForSeek();
        seekSpringNextRender = true;
    }

    /** Use the ordinary non-spring scroll transition after a render resume. */
    public void easeScrollOnNextRender() {
        cancelUserScrollForSeek();
        seekEaseNextRender = true;
    }

    /**
     * Immediately leave drag/fling/idle-hold mode before a lyric or progress seek.
     * Safe to call again when the seek revision reaches the compositor.
     */
    public void cancelUserScrollForSeek() {
        userScrollActive = false;
        userDragging = false;
        userFling = false;
        userReturning = false;
        userFlingVel = 0f;
        dragSampleCount = 0;
        userHoldAnchor = Integer.MIN_VALUE;
    }

    public void setLyrics(List<LyricLine> newLines) {
        clearLayoutCache();
        boolean linearPlainLrc = Boolean.TRUE.equals(LyricConfig.instance.linearAnimForPlainLrc.getValue());
        LyricTimeline.Prepared prepared = LyricTimeline.prepare(newLines, linearPlainLrc);
        this.lines = prepared.lines;
        this.animatablePerToken = prepared.animatablePerToken;
        this.wordGlowSupported = prepared.perSyllableSource;
        this.groups = prepared.groups;
        this.lineToGroup = prepared.lineToGroup;

        this.activeGroupIndex = -1;
        this.scrollAnim.setValue(0);
        this.seekAnim.setValue(0);
        this.lineSpringInit = false;
        this.seekEaseNextRender = false;
        this.seekEaseActive = false;
        this.seekSpringNextRender = false;
        this.seekSpringActive = false;
        this.springAnchorPrev = Integer.MIN_VALUE;
        this.renderedAnchorPrev = Integer.MIN_VALUE;
        // Drop any manual scroll from the previous track.
        this.userScrollActive = false;
        this.userDragging = false;
        this.userFling = false;
        this.userReturning = false;
        this.userScrollPrevAnchor = Integer.MIN_VALUE;
        this.userHoldAnchor = Integer.MIN_VALUE;
    }

    private void clearLayoutCache() {
        closeRows(cachedShapedRows);
        highResRowLru.clear();
        closeTexts(cachedRomajiRows);
        closeTexts(cachedTranslationRows);
        cachedShapedRows = null;
        cachedLayoutSyllables = null;
        cachedRomajiRows = null;
        cachedTranslationRows = null;
        cachedRowStarts = null;
        cachedSylWidths = null;
        cachedLineHeights = null;
        layoutKeyLines = null;
        layoutKeyLyricFont = null;
        layoutKeySubFont = null;
        layoutKeyBgFont = null;
    }

    private static void closeRows(ShapedRow[][] rows) {
        if (rows == null) return;
        for (ShapedRow[] line : rows) {
            if (line == null) continue;
            for (ShapedRow row : line) if (row != null) row.close();
        }
    }

    private static void closeTexts(ShapedText[][] rows) {
        if (rows == null) return;
        for (ShapedText[] line : rows) {
            if (line == null) continue;
            for (ShapedText row : line) if (row != null) row.close();
        }
    }


    /** Screen-space {top, bottom} of the currently-lit lines from the last
     *  {@link #render} call, eased over time so a line joining the lit set crossfades
     *  the edge blur instead of snapping it; null if nothing is lit (interlude /
     *  intro), letting the compositor fall back to its fixed plateau. Called once per
     *  frame by the compositor. */
    public float[] litBandBounds() {
        if (!litBandValid) { litBandSmoothInit = false; return null; }
        long now = System.nanoTime();
        if (!litBandSmoothInit) {
            litBandTopSmooth = litBandTop;
            litBandBottomSmooth = litBandBottom;
            litBandSmoothInit = true;
        } else {
            float dt = (now - litBandSmoothNs) / 1_000_000_000f;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) {
                float a = 1f - (float) Math.exp(-dt / LIT_BAND_TAU);
                litBandTopSmooth += (litBandTop - litBandTopSmooth) * a;
                litBandBottomSmooth += (litBandBottom - litBandBottomSmooth) * a;
            }
        }
        litBandSmoothNs = now;
        litBandResult[0] = litBandTopSmooth;
        litBandResult[1] = litBandBottomSmooth;
        return litBandResult;
    }

    public void render(Canvas canvas, float leftX, float topY,
                       float columnWidth, float columnHeight, long positionMs) {
        // Snapshot mutable state so a concurrent setLyrics() mid-frame can't
        // replace lines/groups/lineToGroup underneath us (ArrayIndexOutOfBounds).
        final java.util.List<LyricLine> lines = this.lines;
        final java.util.List<LyricTimeline.Group> groups = this.groups;
        final int[] lineToGroup = this.lineToGroup;
        if (lines.isEmpty()) return;
        final boolean resumeEase = seekEaseNextRender;
        seekEaseNextRender = false;
        final boolean explicitSeek = seekSpringNextRender;
        seekSpringNextRender = false;

        LyricConfig cfg = LyricConfig.instance;
        int lyricFontSize = cfg.lyricFontSize.getValue();
        int subFontSize = cfg.subFontSize.getValue();
        // BG font derived from the main lyric font (~70%) — the standalone
        // bgFontSize config was tuned independently and ended up reading
        // visually overweight against the active line. Tying it to the
        // lyric size keeps the BG legibly smaller across user font-size
        // changes too.
        int bgFontSize = Math.max(10, Math.round(lyricFontSize * 0.7f));
        // lineGap forced to 0 — line-height (ROW_HEIGHT_RATIO * fontSize)
        // already carries enough vertical breathing room, and any extra
        // gap made the active line drift toward the column edge during
        // group transitions.
        float lineGap = 0f;
        Fonts.Weight weight = toFontsWeight(cfg.fontWeight.getValue());
        float rowHeightRatio = cfg.lineSpacing.getValue();

        // Spring physics toggle: retune the scroll spring only when the flag flips
        // (carries current value/velocity into the new tuning — no snap).
        boolean spring = Boolean.TRUE.equals(cfg.springPhysics.getValue());
        boolean scaleOn = Boolean.TRUE.equals(cfg.scaleEmphasis.getValue());
        boolean glowOn = Boolean.TRUE.equals(cfg.glow.getValue());
        boolean shadowOn = Boolean.TRUE.equals(cfg.dropShadow.getValue());
        int springMode = spring ? 1 : 0;
        if (springMode != lastSpringMode) {
            // scrollAnim only drives the non-spring fallback; per-line springs
            // handle the cascade in spring mode and are re-seeded next frame.
            scrollAnim.setParams(SCROLL_STIFFNESS_FIRM, SCROLL_DAMPING_FIRM);
            lineSpringInit = false;
            lastSpringMode = springMode;
        }

        Font lyricFont = Fonts.get(weight, lyricFontSize);
        Font subFont = Fonts.get(weight, subFontSize);
        Font bgFont = Fonts.get(weight, bgFontSize);

        // Animation-friendly font flags. Skia defaults snap text baselines
        // to integer pixels (isBaselineSnapped=true) and grid-fit glyphs
        // via hinting — so a smooth fractional translateY would still
        // render at integer y, giving the "jumps several pixels per
        // frame" feel the user reported. Disabling baseline snap + going
        // to subpixel positioning makes the lift continuous on the GPU.
        configureForAnimation(lyricFont);
        configureForAnimation(bgFont);
        // subFont is static text (no animation) but we still want it crisp
        // and consistent with the lyric font's anti-alias level.
        configureForAnimation(subFont);

        float rowHeightLyric = lyricFontSize * rowHeightRatio;
        float rowHeightLyricWrap = lyricFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float rowHeightBg = bgFontSize * rowHeightRatio;
        float rowHeightBgWrap = bgFontSize * WRAPPED_ROW_HEIGHT_RATIO;
        float subLineHeight = subFontSize * SUB_ROW_HEIGHT_RATIO;

        boolean showRomaji = cfg.showRomaji.getValue();
        boolean showTranslation = cfg.showTranslation.getValue();

        // ---- Layout pass. Wrapping + per-line heights depend only on the inputs
        // below, NOT on the play head, so compute them once and cache. HarfBuzz is
        // intentionally confined to this rebuild; playback frames only read TextBlob
        // and caret arrays.
        int n = lines.size();
        int colW = Math.round(columnWidth);
        boolean layoutValid = cachedRowStarts != null
                && cachedShapedRows != null
                && cachedLayoutSyllables != null
                && layoutKeyLines == lines
                && layoutKeyN == n
                && layoutKeyLyricSize == lyricFontSize
                && layoutKeySubSize == subFontSize
                && layoutKeyColW == colW
                && layoutKeyWeight == weight
                && layoutKeyRowRatio == rowHeightRatio
                && layoutKeyRomaji == showRomaji
                && layoutKeyTranslation == showTranslation
                && layoutKeyScale == scaleOn
                && layoutKeyLyricFont == lyricFont
                && layoutKeySubFont == subFont
                && layoutKeyBgFont == bgFont;
        if (!layoutValid) {
            int[][] rowStarts = new int[n][];
            float[] lineHeights = new float[n];
            ShapedText[][] romajiRows = new ShapedText[n][];
            ShapedText[][] translationRows = new ShapedText[n][];
            ShapedRow[][] shapedRows = new ShapedRow[n][];
            List<List<Syllable>> layoutSyllables = new ArrayList<>(n);
            float[][] sylWidths = new float[n][];
            for (int i = 0; i < n; i++) {
                LyricLine line = lines.get(i);
                boolean isBg = LyricTimeline.isBackground(line.vocalChannel);
                Font font = isBg ? bgFont : lyricFont;
                float rowHeight = isBg ? rowHeightBg : rowHeightLyric;

                // Wrap against the EMPHASIZED width: a main line scales up to
                // EMPHASIS_SCALE when active, so break it as if the column were
                // 1/EMPHASIS_SCALE narrower — then the scaled-up line fills the
                // real column exactly instead of overflowing and clipping mid-word.
                // BG lines never scale past 1.0, and when emphasis is off no line
                // scales, so both wrap to the full column.
                float wrapW = (isBg || !scaleOn) ? columnWidth : columnWidth / EMPHASIS_SCALE;

                List<Syllable> rowSyllables = splitOversizedSyllables(
                        line.syllables, font, wrapW);
                layoutSyllables.add(rowSyllables);
                float[] widths = shapeSyllableAdvances(rowSyllables, font);
                sylWidths[i] = widths;
                rowStarts[i] = LyricTextLayout.wrapStarts(rowSyllables, widths, wrapW);
                int subRowCount = Math.max(1, rowStarts[i].length - 1);
                shapedRows[i] = new ShapedRow[subRowCount];
                for (int r = 0; r < subRowCount; r++) {
                    int from = rowStarts[i][r];
                    int to = rowStarts[i][r + 1];
                    shapedRows[i][r] = shapeMainRow(rowSyllables, from, to, font);
                }

                float lh = rowHeight + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                boolean hasSub = (line.romaji != null && showRomaji) || (line.translation != null && showTranslation);
                // Wrapped rows use the tight wrap height, so a sub-line sitting right
                // under the last row feels cramped — give it a little extra breathing
                // room (reserved here so neighbours don't overlap; drawn at subY).
                if (hasSub && subRowCount > 1) lh += WRAP_SUB_GAP;
                if (line.romaji != null && showRomaji) {
                    romajiRows[i] = shapeWrappedText(line.romaji, subFont, wrapW);
                    lh += subLineHeight * romajiRows[i].length;
                }
                if (line.translation != null && showTranslation) {
                    translationRows[i] = shapeWrappedText(line.translation, subFont, wrapW);
                    lh += subLineHeight * translationRows[i].length;
                }
                lh += lineGap;
                // BG lines reserve their full layout height upfront so neighbouring
                // lines never shift when the BG scales in / collapses.
                lineHeights[i] = lh;
            }
            closeRows(cachedShapedRows);
            closeTexts(cachedRomajiRows);
            closeTexts(cachedTranslationRows);
            cachedRowStarts = rowStarts;
            cachedLineHeights = lineHeights;
            cachedRomajiRows = romajiRows;
            cachedTranslationRows = translationRows;
            cachedShapedRows = shapedRows;
            cachedLayoutSyllables = layoutSyllables;
            cachedSylWidths = sylWidths;
            layoutKeyLines = lines;
            layoutKeyN = n;
            layoutKeyLyricSize = lyricFontSize;
            layoutKeySubSize = subFontSize;
            layoutKeyColW = colW;
            layoutKeyWeight = weight;
            layoutKeyRowRatio = rowHeightRatio;
            layoutKeyRomaji = showRomaji;
            layoutKeyTranslation = showTranslation;
            layoutKeyScale = scaleOn;
            layoutKeyLyricFont = lyricFont;
            layoutKeySubFont = subFont;
            layoutKeyBgFont = bgFont;
        }
        int[][] rowStarts = cachedRowStarts;
        float[] lineHeights = cachedLineHeights;

        // Font vertical metrics are invariant per (face,size) but Font.getMetrics()
        // allocates a fresh FontMetrics on every call — pull them once per frame
        // instead of per visible row.
        float lyricDescent = lyricFont.getMetrics().getDescent();
        float lyricAscent = lyricFont.getMetrics().getAscent();
        float bgDescent = bgFont.getMetrics().getDescent();
        float bgAscent = bgFont.getMetrics().getAscent();

        // Interlude row height — DYNAMIC, play-head driven. Grows 0 → full as the
        // play head nears the gap, holds, collapses as the next group starts; lines
        // below push down / spring back. So this and the cumulative tops below are
        // the only layout work that genuinely runs every frame. Buffers are reused.
        if (interludeBuf.length != groups.size()) interludeBuf = new float[groups.size()];
        float[] interludeBefore = interludeBuf;
        for (int gi = 0; gi < groups.size(); gi++) {
            interludeBefore[gi] = 0f;
            long prevEnd = (gi == 0) ? 0L : groups.get(gi - 1).endMs;
            long currStart = groups.get(gi).startMs;
            long effectiveEnd = currStart - INTERLUDE_TRAIL_TRIM_MS;
            long gap = effectiveEnd - prevEnd;
            if (gap < INTERLUDE_THRESHOLD_MS) continue;
            interludeBefore[gi] = computeInterludeSlot(positionMs, prevEnd, effectiveEnd);
        }

        // Line positions are STATIC w.r.t. the zoom: the depth scale is a purely
        // visual, centre-anchored transform that doesn't move the line's centre, so
        // it never feeds back into the scroll target. (An earlier version reflowed
        // line heights with the zoom, which made the target drift while the spring
        // chased it — the "bounce back".) Lines stack at their natural heights.

        // Per-frame effective heights: a BG line's slot collapses to nothing until its
        // group is FOCUSED, then opens to full height. Driven by the group's activeK
        // (focus), NOT the BG text's own pop — so the space is reserved the moment focus
        // lands (Apple-Music), and an idle / upcoming / already-sung line shows no empty
        // gap. The scroll compensation below turns each opening into the main line rising
        // rather than the lines beneath being shoved down.
        if (effHeightsBuf.length != n) effHeightsBuf = new float[n];
        float[] effHeights = effHeightsBuf;
        for (int i = 0; i < n; i++) {
            float h = lineHeights[i];
            if (LyricTimeline.isBackground(lines.get(i).vocalChannel)) {
                h *= computeActiveK(positionMs, groups.get(lineToGroup[i]));
            }
            effHeights[i] = h;
        }

        // Cumulative tops = stacked effective heights + the per-frame interlude slots.
        if (lineTopsBuf.length != n) lineTopsBuf = new float[n];
        float[] lineTops = lineTopsBuf;
        for (int i = 0; i < n; i++) {
            float prevBottom = i == 0 ? 0f : lineTops[i - 1] + effHeights[i - 1];
            // First line of a group with a preceding interlude gets the dot-row slot
            // inserted above it.
            int gi = lineToGroup[i];
            if (!groups.isEmpty() && gi >= 0 && gi < groups.size()
                    && groups.get(gi).from == i && interludeBefore[gi] > 0f) {
                prevBottom += interludeBefore[gi];
            }
            lineTops[i] = prevBottom;
        }

        // Switch as soon as the upcoming group enters its delayed visual fade-in window.
        // Scroll timing follows the first visible brightening of the next line, while
        // the previous line's sweep/fade continues independently through its own endMs.
        int anchorGroup = -1;
        int timelineGroupIndex = -1;
        for (int gi = 0; gi < groups.size(); gi++) {
            LyricTimeline.Group g = groups.get(gi);
            if (fadeInStartMs(g) > positionMs) break;
            anchorGroup = gi;
            if (g.startMs <= positionMs) timelineGroupIndex = gi;
        }
        activeGroupIndex = anchorGroup;

        LyricTimeline.Group activeGroup = (activeGroupIndex >= 0 && activeGroupIndex < groups.size())
                ? groups.get(activeGroupIndex) : null;
        LyricTimeline.Group timelineGroup = (timelineGroupIndex >= 0 && timelineGroupIndex < groups.size())
                ? groups.get(timelineGroupIndex) : null;

        // Scroll target = the centre of the whole simultaneously-singing block. This
        // includes the active group (main + BG rows) and any immediately preceding
        // groups whose REAL time ranges overlap it. TTML duets are separate groups —
        // e.g. one agent can keep singing for several seconds after the other starts —
        // so centring only the newest group pushes the still-active upper singer out.
        // The active/animation anchor remains the newest group, preserving the early
        // handoff timing; only viewport placement uses the combined overlap block.
        //
        // EXCEPTION: when the play head is in an interlude (gap between
        // active group's end and next group's start ≥ INTERLUDE_THRESHOLD_MS),
        // the scroll target shifts to the reserved dot-row slot — the
        // dots scroll into the centre position like a real line, then
        // hand back to the next group's main centre as the interlude ends.
        float targetScroll = 0f;
        boolean inInterlude = false;
        int interludeNextGroup = -1;
        long interludeStartMs = 0L;  // gap start (0 for intro, prev.endMs otherwise)
        if (activeGroup != null) {
            int blockFromGroup = activeGroupIndex;
            while (blockFromGroup > 0) {
                LyricTimeline.Group firstIncluded = groups.get(blockFromGroup);
                LyricTimeline.Group previous = groups.get(blockFromGroup - 1);
                if (previous.endMs <= firstIncluded.startMs) break;
                // Do not let short pairwise overlaps form an indefinitely long
                // chain. Once the preceding group has completed its own visual
                // fade-out it no longer occupies viewport space, even if it used
                // to overlap the first group still included below it.
                if (computeActiveK(positionMs, previous) <= BG_VISIBLE_THRESHOLD) break;
                blockFromGroup--;
            }
            int blockFrom = groups.get(blockFromGroup).from;
            float blockTop = lineTops[blockFrom];
            // Finish at the newest active group's last row; groups after it have not
            // entered their fade/anchor window yet and must not affect placement.
            float groupBottom = lineTops[activeGroup.from] + effHeights[activeGroup.from];
            for (int j = activeGroup.from + 1; j < activeGroup.to; j++) {
                groupBottom = lineTops[j] + effHeights[j];
            }
            targetScroll = (blockTop + groupBottom) * 0.5f;
            // If the group is taller than the space above the 35% alignment line,
            // pure centring would still clip its first row. Bias the group downward
            // just enough to retain that row; lower rows may use the larger space below.
            float maxScrollKeepingTop = blockTop + columnHeight * ALIGN_POSITION
                    - ACTIVE_GROUP_TOP_MARGIN_PX;
            targetScroll = Math.min(targetScroll, maxScrollKeepingTop);
        }

        // Interlude detection covers THREE shapes:
        //   1. Intro: positionMs < groups[0].startMs, gap = [0, group[0].start)
        //   2. Between groups: activeGroup just finished, gap to next
        //   3. Outro: after last group — no dots (no "next" to anchor to)
        // End trimmed by INTERLUDE_TRAIL_TRIM_MS so the dots collapse a
        // moment before the next line sings.
        LyricTimeline.Group nextGroup = null;
        long gapStart = -1L;
        if (timelineGroup == null && !groups.isEmpty()
                && positionMs < groups.get(0).startMs) {
            // Intro
            nextGroup = groups.get(0);
            gapStart = 0L;
            interludeNextGroup = 0;
        } else if (timelineGroup != null && timelineGroupIndex + 1 < groups.size()
                && positionMs >= timelineGroup.endMs) {
            // Between groups
            nextGroup = groups.get(timelineGroupIndex + 1);
            gapStart = timelineGroup.endMs;
            interludeNextGroup = timelineGroupIndex + 1;
        }
        if (nextGroup != null) {
            long effectiveEnd = nextGroup.startMs - INTERLUDE_TRAIL_TRIM_MS;
            long gap = effectiveEnd - gapStart;
            if (positionMs < effectiveEnd && gap >= INTERLUDE_THRESHOLD_MS) {
                inInterlude = true;
                interludeStartMs = gapStart;
                // Once the next line enters its visual fade-in window, let the lyric
                // anchor move immediately but keep rendering the dots through their
                // own exit timeline. Before that handoff, dots remain the scroll target.
                if (activeGroupIndex != interludeNextGroup) {
                    float slotH = interludeBefore[interludeNextGroup];
                    float dotsTop = lineTops[nextGroup.from] - slotH;
                    targetScroll = dotsTop + slotH * 0.5f;
                }
            } else if (positionMs >= effectiveEnd && positionMs < nextGroup.startMs
                    && gap >= INTERLUDE_THRESHOLD_MS) {
                // EXIT TRAIL — dots no longer visible (we passed
                // effectiveEnd) but the next group hasn't started, so
                // the default activeGroup fallback would point back to
                // the previous group and yank scroll downward. Anchor
                // on the upcoming group now so scroll keeps moving
                // monotonically upward toward it.
                int nIdx = nextGroup.from;
                float nTop = lineTops[nIdx];
                float nBottom = nTop + lineHeights[nIdx];
                targetScroll = (nTop + nBottom) * 0.5f;
                interludeNextGroup = -1;
            } else {
                interludeNextGroup = -1;
            }
        }

        // Scroll bounds shared by the auto-follow AND manual scroll, so neither can run
        // a line past an edge into blank. When the lyrics are taller than the column,
        // pin the first line's top to the column top and the last line's bottom to the
        // column bottom; the active line still centres (ALIGN_POSITION) once there is
        // enough lyric above/below it. Shorter-than-column lyrics don't scroll.
        if (n > 0) {
            float contentEnd = lineTops[n - 1] + effHeights[n - 1];
            if (contentEnd > columnHeight) {
                float pad = columnHeight * SCROLL_EDGE_PAD;
                scrollMin = columnHeight * ALIGN_POSITION - pad;
                scrollMax = contentEnd - columnHeight * (1f - ALIGN_POSITION) + pad;
            } else {
                scrollMin = scrollMax = targetScroll;
            }
            if (targetScroll < scrollMin) targetScroll = scrollMin;
            else if (targetScroll > scrollMax) targetScroll = scrollMax;
        }

        float centerY = topY + columnHeight * ALIGN_POSITION;
        lastCenterY = centerY;

        int anchorIdx = activeGroup != null ? activeGroup.from : 0;
        // The draw window normally tracks the active line, but a manual scroll can pull
        // the view far from it — center the window on the on-screen scroll position then,
        // or the lines you scrolled to (being outside anchorIdx ± VISIBLE_RADIUS) are
        // never drawn and the page goes blank. lastScrollY is the previous frame's offset.
        int windowCenter = userScrollActive ? lineIndexAt(lineTops, n, lastScrollY) : anchorIdx;
        int start = Math.max(0, windowCenter - VISIBLE_RADIUS);
        int end = Math.min(n, windowCenter + VISIBLE_RADIUS + 1);

        // Per-line scroll springs (spring mode only). Each visible line chases its
        // resting top `centerY + lineTops[i] - targetScroll`; the global scrollAnim
        // above still drives the rigid fallback when spring is off.
        long nowNs = System.nanoTime();
        double springDt = 0.0;
        int previousRenderedAnchor = renderedAnchorPrev;
        boolean anchorChangedThisFrame = previousRenderedAnchor != Integer.MIN_VALUE
                && anchorIdx != previousRenderedAnchor;
        renderedAnchorPrev = anchorIdx;
        boolean largeAnchorJump = !explicitSeek && !resumeEase
                && !seekEaseActive && !seekSpringActive
                && previousRenderedAnchor != Integer.MIN_VALUE
                && Math.abs(anchorIdx - previousRenderedAnchor) > SNAP_JUMP_LINES;
        boolean startSpringSeek = explicitSeek;
        boolean startNonlinearEase = resumeEase || largeAnchorJump;
        float seekFromScroll = lastScrollY;
        if ((startSpringSeek || startNonlinearEase) && spring && !userScrollActive
                && !seekEaseActive && !seekSpringActive
                && springAnchorPrev >= 0 && springAnchorPrev < n
                && springAnchorPrev < lineCurTop.length) {
            // Recover the currently drawn rigid offset from the old anchor line so
            // the seek tween begins exactly where the per-line cascade was visible.
            seekFromScroll = centerY + lineTops[springAnchorPrev] - lineCurTop[springAnchorPrev];
        }
        if (spring) {
            if (lineCurTop.length != n) {
                lineCurTop = new float[n];
                lineVelTop = new float[n];
                lineSpringInit = false;
            }
            if (anchorIdx != springAnchorPrev) {
                int previousAnchor = springAnchorPrev;
                // Direction the column is travelling: +1 advancing (content scrolls
                // up), -1 seeking back (content scrolls down). Drives which side of
                // the active line leads the cascade.
                if (previousAnchor != Integer.MIN_VALUE) {
                    cascadeDir = (anchorIdx > previousAnchor) ? 1 : -1;
                }
                springAnchorPrev = anchorIdx;
                springAnchorChangeNs = nowNs;
            }
            springDt = (nowNs - springLastNs) / 1_000_000_000.0;
            if (springDt > 0.05) springDt = 0.05;
            if (springDt < 0.0) springDt = 0.0;
            springLastNs = nowNs;
        }
        if (seekEaseActive && !startNonlinearEase && !startSpringSeek && anchorChangedThisFrame) {
            // Playback reached the next line before the seek tween finished. Hand
            // control back at the currently drawn positions; the per-line springs
            // continue from lineCurTop on this very frame instead of the tween later
            // snapping from its stale destination to the new anchor.
            seekEaseActive = false;
            scrollAnim.setValue(lastScrollY);
        }
        if (startSpringSeek) {
            // Match the old seek path: seed one rigid, near-critically-damped
            // global spring at the currently drawn offset and let it chase the
            // live target until settled. Per-line springs stay synchronized below.
            boolean wasSpringSeeking = seekSpringActive;
            seekEaseActive = false;
            seekSpringActive = true;
            // Progress-bar dragging produces several seek revisions. Preserve the
            // spring's velocity across those retargets, exactly as the old path did.
            if (!wasSpringSeeking) seekAnim.setValue(seekFromScroll);
            seekAnim.setTargetPosition(targetScroll);
            scrollAnim.setValue(seekFromScroll);
        }
        if (startNonlinearEase) {
            // Render resumes and unclassified large discontinuities move the column
            // rigidly with the decelerating tween. Explicit seeks use the old spring.
            seekSpringActive = false;
            seekEaseActive = true;
            seekEaseStartNs = nowNs;
            seekEaseFrom = seekFromScroll;
            seekEaseTo = targetScroll;
            scrollAnim.setValue(seekFromScroll);
        }
        double sinceAnchorChange = (nowNs - springAnchorChangeNs) / 1_000_000_000.0;

        // The global scroll value drives the rigid fallback when per-line spring
        // physics is off. Discontinuous transitions temporarily move the same rigid
        // column through either the old seek spring or the quartic resume tween.
        boolean rigidMode = !spring || seekEaseActive || seekSpringActive;

        // A big position jump (progress-bar seek) cancels manual scroll so the column
        // snaps back to following the play head via the normal ease.
        if (userScrollActive && (startSpringSeek || startNonlinearEase
                || (userScrollPrevAnchor != Integer.MIN_VALUE
                && Math.abs(anchorIdx - userScrollPrevAnchor) > SNAP_JUMP_LINES))) {
            cancelUserScrollForSeek();
            scrollAnim.setValue(lastScrollY);
        }
        userScrollPrevAnchor = anchorIdx;

        float scrollY;
        if (seekSpringActive) {
            scrollY = (float) seekAnim.animate(targetScroll);
            if (seekAnim.arrived()) {
                scrollY = targetScroll;
                seekSpringActive = false;
            }
            // Keep fallback/manual-return state warm for a seamless handoff.
            scrollAnim.setValue(scrollY);
        } else if (seekEaseActive) {
            float t = Math.min(1f, (nowNs - seekEaseStartNs)
                    / (float) DISCONTINUITY_EASE_DURATION_NS);
            float inv = 1f - t;
            // Quartic ease-out drops below the previous cubic curve's velocity after
            // the first quarter, leaving a longer, calmer approach to the destination.
            float inv2 = inv * inv;
            float eased = 1f - inv2 * inv2;
            scrollY = seekEaseFrom + (seekEaseTo - seekEaseFrom) * eased;
            if (t >= 1f) {
                // Do not snap to a target that drifted while the tween was running
                // (e.g. a BG row expanding). Finish at the tween's own destination;
                // normal line following picks up any tiny residual continuously.
                scrollY = seekEaseTo;
                seekEaseActive = false;
            }
            // Keep the unused fallback spring synchronized so handing control back
            // after the tween cannot reintroduce old velocity.
            scrollAnim.setValue(scrollY);
        } else if (userScrollActive) {
            // Hand-controlled: move the whole column rigidly to the user's offset (or
            // the scrollAnim ease while returning); the highlight keeps tracking pos.
            rigidMode = true;
            scrollY = stepUserScroll(targetScroll, nowNs, anchorIdx);
        } else {
            scrollY = (float) scrollAnim.animate(targetScroll);
        }
        lastScrollY = scrollY;

        // Dynamic scroll-spring tuning (AMLL): steady during an interlude, else
        // stiffer the faster lines are arriving (shorter gap to the previous line).
        double scrollStiffness;
        double scrollDamping;
        if (inInterlude) {
            scrollStiffness = SCROLL_STIFFNESS_INTERLUDE;
            scrollDamping = SCROLL_DAMPING_INTERLUDE;
        } else {
            LyricTimeline.Group prevG = (activeGroupIndex > 0) ? groups.get(activeGroupIndex - 1) : null;
            double interval = (activeGroup != null && prevG != null)
                    ? (activeGroup.startMs - prevG.startMs) : SCROLL_INTERVAL_MAX_MS;
            double ci = Math.max(SCROLL_INTERVAL_MIN_MS, Math.min(SCROLL_INTERVAL_MAX_MS, interval));
            double ratio = Math.pow(1.0 - (ci - SCROLL_INTERVAL_MIN_MS)
                    / (SCROLL_INTERVAL_MAX_MS - SCROLL_INTERVAL_MIN_MS), 0.2);
            scrollStiffness = SCROLL_STIFFNESS_MIN + ratio * (SCROLL_STIFFNESS_MAX - SCROLL_STIFFNESS_MIN);
            scrollDamping = Math.sqrt(scrollStiffness) * SCROLL_DAMPING_MULT;
        }

        // Per-line cascade delays. The active line plus everything on the LEADING
        // side (the side the column is moving toward) move in lockstep — spacing is
        // preserved so the active line never springs into a still-stationary
        // neighbour. Only the TRAILING side cascades, with a shrinking step, for a
        // wave. Leading side flips with travel direction so seeking either way is
        // overlap-free: advancing (scroll up) → top leads, lines below trail;
        // seeking back (scroll down) → bottom leads, lines above trail.
        if (cascadeDelayBuf.length < n) cascadeDelayBuf = new double[n];
        for (int i = start; i < end; i++) cascadeDelayBuf[i] = 0.0;
        double cascDelay = 0.0;
        double cascStep = LINE_DELAY_S;
        if (cascadeDir >= 0) {
            for (int i = Math.max(start, anchorIdx + 1); i < end; i++) {
                cascDelay += cascStep;
                cascStep /= LINE_DELAY_DECAY;
                cascadeDelayBuf[i] = cascDelay;
            }
        } else {
            for (int i = Math.min(end - 1, anchorIdx - 1); i >= start; i--) {
                cascDelay += cascStep;
                cascStep /= LINE_DELAY_DECAY;
                cascadeDelayBuf[i] = cascDelay;
            }
        }

        litBandValid = false;
        for (int i = start; i < end; i++) {
            LyricLine line = lines.get(i);
            LyricTimeline.Group myGroup = groups.get(lineToGroup[i]);

            float activeK = computeActiveK(positionMs, myGroup);

            LyricLine.VocalChannel ch = line.vocalChannel;
            boolean isBg = LyricTimeline.isBackground(ch);
            boolean alignRight = ch == LyricLine.VocalChannel.DUET_RIGHT
                    || ch == LyricLine.VocalChannel.BACKGROUND_RIGHT;

            Font font = isBg ? bgFont : lyricFont;
            float rowHeight = isBg ? rowHeightBg : rowHeightLyric;
            float descent = isBg ? bgDescent : lyricDescent;
            float ascent = isBg ? bgAscent : lyricAscent;
            // baseAlpha interpolates idle ↔ active so the line's overall
            // brightness rises/falls with the group transition rather
            // than snapping at the boundary.
            float idleBase = isBg ? 0.18f : 0.22f;
            float activeBase = isBg ? 0.70f : 1f;
            float baseAlpha = idleBase + (activeBase - idleBase) * activeK;

            // Top of this line in screen space. Per-line spring mode: each line
            // springs to its resting top with a per-line stagger (cascade). Rigid
            // mode (spring off, or a big seek easing over): the single global
            // scrollAnim offset — and we keep lineCurTop synced to it so the per-line
            // spring resumes seamlessly from these positions when the ease ends.
            float restTop = centerY + lineTops[i] - targetScroll;
            float lineYTop;
            if (rigidMode) {
                lineYTop = centerY + lineTops[i] - scrollY;
                if (spring) {
                    lineCurTop[i] = lineYTop;
                    lineVelTop[i] = 0f;
                }
            } else {
                boolean wasVisible = i >= prevVisStart && i < prevVisEnd;
                if (!lineSpringInit || !wasVisible) {
                    lineCurTop[i] = restTop;
                    lineVelTop[i] = 0f;
                } else {
                    if (sinceAnchorChange >= cascadeDelayBuf[i] && springDt > 0.0) {
                        stepLineSpring(i, restTop, springDt, scrollStiffness, scrollDamping);
                    }
                }
                lineYTop = lineCurTop[i];
            }

            // Viewport cull: VISIBLE_RADIUS keeps far lines in the spring window
            // (stepped just above), but only a handful fit in the column — skip the
            // draw work (saveLayer/sweep/glow/drawString) for lines fully outside it.
            // Margin covers the emphasis zoom + glow bleed.
            if (lineYTop + lineHeights[i] < topY - 32f || lineYTop > topY + columnHeight + 32f) {
                continue;
            }

            int[] starts = rowStarts[i];
            int subRowCount = Math.max(1, starts.length - 1);

            // Track widest sub-row so right-aligned sub-lines line up with
            // the visual right edge of the lyric block.
            float maxRowWidth = 0f;
            float maxRowRightX = leftX; // for sub-line right-anchor

            // BG lines now occupy their own pre-reserved slot in the
            // layout (lineHeights[i] = real height). Anchor stays at the
            // slot top — no longer overlaps the main line. The scale
            // animation pops the BG content out of its own slot, but the
            // slot itself is always there so neighbouring lines never
            // shift when the BG activates / collapses.
            // Every line gets a scale transform. BG lines keep their pop-in/out
            // scale (anchored at their slot top). Main lines use depth scaling —
            // deselected 0.98× growing to the active group's 1.14× emphasis — driven
            // by the scroll spring's progress so the zoom lands exactly as the line
            // settles, and anchored at the line's CENTRE so growing it never shifts
            // its centre (that downward push at arrival was the "bounce").
            float anchorX = alignRight ? (leftX + columnWidth) : leftX;
            float scale;
            float anchorY;
            if (isBg) {
                float bgScaleK = computeBgScaleK(positionMs, myGroup);
                if (bgScaleK < BG_VISIBLE_THRESHOLD) continue;
                scale = BG_SCALE_IDLE + (1f - BG_SCALE_IDLE) * bgScaleK;
                anchorY = lineYTop;
            } else if (scaleOn) {
                float mainTextH = rowHeight + (subRowCount - 1) * rowHeightLyricWrap;
                float lineCenter = lineYTop + mainTextH * 0.5f;
                float emph;
                if (spring) {
                    // Proximity to the fixed centre line (where the active line
                    // settles), NOT to the line's own target. The spring position is
                    // continuous, so this never jumps when the target does — the
                    // outgoing line shrinks smoothly as it springs away, the incoming
                    // one grows as it springs in. No flash, no bounce.
                    float ref = Math.max(40f, lineHeights[i]);
                    float prog = 1f - Math.min(1f, Math.abs(lineCenter - centerY) / ref);
                    emph = activeK * prog;
                } else {
                    emph = activeK;
                }
                scale = DESELECTED_SCALE + (EMPHASIS_SCALE - DESELECTED_SCALE) * emph;
                anchorY = lineCenter;
            } else {
                scale = 1f;
                anchorY = lineYTop;
            }

            // Grow the lit band to this line's drawn extent when it's clearly active,
            // so a multi-line group (main + BG, or overlapping v1/v2) keeps ALL its
            // lit lines in the edge-blur sharp band — not just the anchor line.
            if (activeK >= 0.5f) {
                float lt = lineYTop, lb = lineYTop + lineHeights[i];
                if (!litBandValid) { litBandTop = lt; litBandBottom = lb; litBandValid = true; }
                else {
                    if (lt < litBandTop) litBandTop = lt;
                    if (lb > litBandBottom) litBandBottom = lb;
                }
            }

            canvas.save();
            canvas.translate(anchorX, anchorY);
            canvas.scale(scale, scale);
            canvas.translate(-anchorX, -anchorY);

            for (int r = 0; r < subRowCount; r++) {
                ShapedRow shapedRow = cachedShapedRows[i][r];
                // Drop leading whitespace on every row: a continuation row inherits the
                // space the source kept at the wrap point, and the first row can carry a
                // leading space from the source line itself (common in JP lyrics) — both
                // would sit the text one space in from the column's left edge.
                float lead = shapedRow.leadingWidth;
                float visWidth = shapedRow.width - lead;
                float rowX = alignRight
                        ? Math.max(leftX, leftX + columnWidth - visWidth)
                        : leftX;

                float wrapRowH = (r == 0) ? rowHeight : (isBg ? rowHeightBgWrap : rowHeightLyricWrap);
                float rowBaselineY = lineYTop + rowHeight + r * wrapRowH - descent - 4f;
                drawShapedRow(cachedLayoutSyllables.get(i), shapedRow, rowX - lead, rowBaselineY,
                        ascent, descent, positionMs, baseAlpha, activeK, animatablePerToken, spring,
                        glowOn, shadowOn);

                if (visWidth > maxRowWidth) {
                    maxRowWidth = visWidth;
                    maxRowRightX = rowX + visWidth;
                }
            }

            // Sub-lines anchor to the lyric block's right edge (right-align)
            // or to leftX (left-align). Y must match the wrapped block's real
            // stacked height (first row full, extra rows at the wrap height) —
            // using subRowCount*rowHeight overshoots and pushes translation /
            // romaji too far below a multi-row line.
            float subY = lineYTop + rowHeight
                    + (subRowCount - 1) * (isBg ? rowHeightBgWrap : rowHeightLyricWrap) + 4f
                    + (subRowCount > 1 ? WRAP_SUB_GAP : 0f);
            subY = drawSubline(leftX, subLineHeight, showRomaji, i, alignRight,
                    baseAlpha, maxRowRightX, subY, cachedRomajiRows, shadowOn);
            subY = drawSubline(leftX, subLineHeight, showTranslation, i, alignRight,
                    baseAlpha, maxRowRightX, subY, cachedTranslationRows, shadowOn);

            canvas.restore();
        }

        if (spring) {
            prevVisStart = start;
            prevVisEnd = end;
            lineSpringInit = true;
        }

        // ---- Interlude dots (AMLL `InterludeDots`, inline in layout) ----
        // The dot row already has its reserved INTERLUDE_DOTS_ROW_H slot
        // in lineTops via interludeBefore[]. When in an interlude, scroll
        // has shifted that slot to the centre — we just draw the dots in
        // it. Math is a 1:1 port of amll-dev/applemusic-like-lyrics/.../
        // interlude-dots.ts.
        if (inInterlude && interludeNextGroup >= 0) {
            LyricTimeline.Group interludeNext = groups.get(interludeNextGroup);
            // Use the trimmed window — same one the slot computeInterludeSlot
            // ramps against — so the dots' internal timeline matches the
            // slot's open/close timeline exactly. interludeStartMs is 0
            // for the intro, or prevGroup.endMs for between-group gaps.
            long effectiveEnd = interludeNext.startMs - INTERLUDE_TRAIL_TRIM_MS;
            long interludeDur = effectiveEnd - interludeStartMs;
            float slotH = interludeBefore[interludeNextGroup];
            if (slotH > 4f) {
                // Top of the upcoming line's reserved dot slot, spring-aware so the
                // dots ride the same cascade as the lines.
                int nf = interludeNext.from;
                float nextTop = (spring && nf >= start && nf < end)
                        ? lineCurTop[nf]
                        : centerY + lineTops[nf] - (spring && !userScrollActive ? targetScroll : scrollY);
                // Centre the dots between the two LINES OF TEXT, not the slot edges.
                // The slot top (nextTop - slotH) sits at the previous line's bottom,
                // but the next line's text starts nextTextOffset below its slot top
                // (line-height leaves that gap above the glyphs). Without accounting
                // for it the dots hug the previous line and drift with line spacing.
                float nextTextOffset = rowHeightLyric + lyricAscent - lyricDescent - 4f;
                float prevTextBottom = nextTop - slotH;
                float nextTextTop = nextTop + nextTextOffset;
                float anchorY = (prevTextBottom + nextTextTop) * 0.5f - INTERLUDE_DOT_RADIUS;
                // Place the dots on the side the upcoming line is aligned to: left for
                // MAIN / left-duet, right for right-channel lines.
                LyricLine.VocalChannel nextCh = lines.get(interludeNext.from).vocalChannel;
                boolean dotsRight = nextCh == LyricLine.VocalChannel.DUET_RIGHT
                        || nextCh == LyricLine.VocalChannel.BACKGROUND_RIGHT;
                float dotsWidth = 2f * INTERLUDE_DOT_RADIUS + 2f * INTERLUDE_DOT_SPACING;
                float dotsX = dotsRight ? Math.max(leftX, leftX + columnWidth - dotsWidth) : leftX;
                renderInterludeDots(canvas, dotsX, anchorY,
                        positionMs - interludeStartMs, interludeDur);
            }
        }
    }

    private float drawSubline(float leftX, float subLineHeight,
                              boolean showRomaji, int i, boolean alignRight,
                              float baseAlpha, float maxRowRightX, float subY,
                              ShapedText[][] cachedRomajiRows, boolean shadowOn) {
        ShapedText[] romajiRows = cachedRomajiRows[i];
        if (romajiRows != null && showRomaji) {
            for (ShapedText romajiRow : romajiRows) {
                drawSubLine(romajiRow, leftX, maxRowRightX, subY,
                        baseAlpha * 0.75f, alignRight, shadowOn);
                subY += subLineHeight;
            }
        }
        return subY;
    }

    /**
     * Time-driven height of the interlude dot slot. Smoothstep-ramps
     * up over 150 ms starting AT the gap (no pre-lead — anticipating
     * the gap made the active line drift up before its sung phase
     * actually ended). Holds full height through the body. Collapses
     * over 150 ms ending at the trimmed gap end.
     */
    private static float computeInterludeSlot(long positionMs, long prevEnd, long currStart) {
        long lead = 150L;
        long trail = 150L;
        if (positionMs < prevEnd || positionMs > currStart) return 0f;
        float t;
        long inside = positionMs - prevEnd;
        if (inside < lead) {
            t = inside / (float) lead;
        } else if (currStart - positionMs < trail) {
            t = (currStart - positionMs) / (float) trail;
        } else {
            t = 1f;
        }
        // Smoothstep — same Hermite curve as our smoothstep helper.
        t = Math.max(0f, Math.min(1f, t));
        float eased = t * t * (3f - 2f * t);
        return INTERLUDE_DOTS_ROW_H * eased;
    }

    // ===== Interlude dots (AMLL port) =====

    /**
     * Three breathing dots shown during interludes. Phase thresholds
     * scale with the actual gap duration: AMLL's fixed 500/1000/2000/
     * 750/375 ms windows assume gaps in the 10-30 s range, but a 2.5 s
     * verse pause needs them compressed proportionally or the dots
     * spend the whole gap fading in / out with no stable middle. We
     * pick {@code min(AMLL_default, gap × fraction)} for every phase
     * — long gaps land on AMLL defaults exactly, short gaps get a
     * fade-in/hold/exit distribution that fits.
     */
    private void renderInterludeDots(Canvas canvas, float leftX, float anchorY,
                                     long currentDuration, long interludeDuration) {
        if (currentDuration < 0L || currentDuration > interludeDuration) return;

        // No "invisible delay" window at the start — AMLL's 500 ms blank
        // before fade-in was the main visible-perceived latency the user
        // hit. Combined with the 300 ms slot lead and the spring scroll
        // catching up, the gap could be nearly a second old before any
        // dot appeared. Start fade-in at 0 so the dots arrive in sync
        // with the slot expanding.
        long fadeInStartMs = 0L;
        long fadeInEndMs = Math.min(600L, (long) (interludeDuration * 0.20));
        long scaleRampMs = Math.min(1500L, (long) (interludeDuration * 0.35));
        long exitScaleMs = Math.min(750L, (long) (interludeDuration * 0.20));
        long exitOpacityMs = Math.min(375L, (long) (interludeDuration * 0.10));
        if (fadeInEndMs <= fadeInStartMs) fadeInEndMs = fadeInStartMs + 1L;

        // Breath cycles: divide the whole interlude into ~1500 ms cycles
        // — each sin oscillation is one breath.
        double breatheDur = interludeDuration
                / Math.ceil(interludeDuration / 1500.0);
        double scale = 1.0;
        double globalOpacity = 1.0;

        // Sin breath modulation: ±5% scale around 1.0 (1/20 amplitude).
        scale *= Math.sin(1.5 * Math.PI
                - (currentDuration / breatheDur) * 2.0) / 20.0 + 1.0;

        // Entry ramp — easeOutExpo over scaleRampMs.
        if (currentDuration < scaleRampMs) {
            scale *= easeOutExpoD(currentDuration / (double) scaleRampMs);
        }

        // Global opacity fade-in window: 0-fadeInStart invisible,
        // fadeInStart..fadeInEnd ramps to 1.
        if (currentDuration < fadeInStartMs) {
            globalOpacity = 0.0;
        } else if (currentDuration < fadeInEndMs) {
            globalOpacity *= (currentDuration - fadeInStartMs)
                    / (double) (fadeInEndMs - fadeInStartMs);
        }

        // Exit: scale collapse via easeInOutBack in final exitScaleMs.
        long remaining = interludeDuration - currentDuration;
        if (remaining < exitScaleMs) {
            scale *= 1.0 - easeInOutBackD(
                    (exitScaleMs - remaining) / (double) exitScaleMs / 2.0);
        }
        // Opacity linear fade in final exitOpacityMs.
        if (remaining < exitOpacityMs) {
            globalOpacity *= Math.max(0.0,
                    Math.min(1.0, remaining / (double) exitOpacityMs));
        }

        // AMLL post-clamp: scale to 70 % of computed value.
        long dotsDur = Math.max(1L, interludeDuration - exitScaleMs);
        scale = Math.max(0.0, scale) * 0.7;
        if (scale < 0.01) return;

        // Per-dot staggered opacity: each dot follows the same ramp
        // shifted by dotsDur/3, clamped to [0.25, 1].
        double op0 = clampD(0.25, (currentDuration * 3.0 / dotsDur) * 0.75, 1.0);
        double op1 = clampD(0.25,
                ((currentDuration - dotsDur / 3.0) * 3.0 / dotsDur) * 0.75, 1.0);
        double op2 = clampD(0.25,
                ((currentDuration - dotsDur * 2.0 / 3.0) * 3.0 / dotsDur) * 0.75, 1.0);

        float dotRadius = INTERLUDE_DOT_RADIUS;
        float spacing = INTERLUDE_DOT_SPACING;
        float cx0 = leftX + dotRadius;
        float cy = anchorY + dotRadius;

        canvas.save();
        canvas.translate(cx0 + spacing, cy);
        canvas.scale((float) scale, (float) scale);
        canvas.translate(-(cx0 + spacing), -cy);
        try {
            double[] ops = {globalOpacity * op0, globalOpacity * op1, globalOpacity * op2};
            for (int i = 0; i < 3; i++) {
                Paint p = dotPaint;
                p.setColor(0xFFFFFFFF);
                float a = (float) Math.max(0.0, Math.min(1.0, ops[i]));
                p.setAlphaf(a);
                p.setAntiAlias(true);
                canvas.drawCircle(cx0 + i * spacing, cy, dotRadius, p);
            }
        } finally {
            canvas.restore();
        }
    }

    private static double easeInOutBackD(double x) {
        double c1 = 1.70158;
        double c2 = c1 * 1.525;
        return x < 0.5
                ? (Math.pow(2 * x, 2) * ((c2 + 1) * 2 * x - c2)) / 2
                : (Math.pow(2 * x - 2, 2) * ((c2 + 1) * (x * 2 - 2) + c2) + 2) / 2;
    }

    private static double easeOutExpoD(double x) {
        if (x >= 1.0) return 1.0;
        return 1.0 - Math.pow(2, -10.0 * x);
    }

    private static double clampD(double lo, double v, double hi) {
        if (v < lo) return lo;
        return Math.min(v, hi);
    }

    private static void configureForAnimation(Font f) {
        f.setBaselineSnapped(false);
        f.setSubpixel(true);
        f.setHinting(FontHinting.NONE);
        f.setEdging(FontEdging.SUBPIXEL_ANTI_ALIAS);
    }

    // Hangul: Syllables + Jamo Extended-B (AC00-D7FF), Jamo (1100-11FF), Compatibility
    // Jamo (3130-318F), Jamo Extended-A (A960-A97F). The bundled PingFang face has none.
    private static boolean needsKorean(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7FF) || (c >= 0x1100 && c <= 0x11FF)
                    || (c >= 0x3130 && c <= 0x318F) || (c >= 0xA960 && c <= 0xA97F)) {
                return true;
            }
        }
        return false;
    }

    // Thai block (0E00-0E7F). The bundled PingFang face has none either.
    private static boolean needsThai(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0E00 && c <= 0x0E7F) return true;
        }
        return false;
    }

    // Hiragana (3040-309F), Katakana (30A0-30FF), Katakana Phonetic Extensions
    // (31F0-31FF), Halfwidth Katakana (FF65-FF9F). NOT shared Han (PingFang already
    // covers that) -- only the kana blocks PingFang SC has no glyphs for at all.
    private static boolean needsJapanese(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF)
                    || (c >= 0xFF65 && c <= 0xFF9F)) {
                return true;
            }
        }
        return false;
    }

    // The Korean/Thai/Japanese fallback face matching `base`'s size and weight when
    // `text` needs one, else `base`. Measure and draw call this with the same
    // (text, base), so cached syllable widths and drawn advances stay aligned.
    private static Font fontForText(String text, Font base) {
        // Fonts.korean/thai/japanese return a cache-owned, cross-frame Font —
        // borrowed, not owned here, so it must NOT be closed (try-with-resources
        // would free it mid-cache). They return null both when the platform ships no
        // face for the script AND when `base` already covers it (a JP/KR-capable
        // system or user-picked font), which is what keeps such text on the user's
        // own font instead of always hopping to a fallback family.
        // noinspection resource
        if (needsKorean(text)) {
            Font ko = Fonts.korean(base);
            if (ko != null) return ko;
        }
        // noinspection resource
        if (needsThai(text)) {
            Font th = Fonts.thai(base);
            if (th != null) return th;
        }
        // noinspection resource
        if (needsJapanese(text)) {
            Font ja = Fonts.japanese(base);
            if (ja != null) return ja;
        }
        return base;
    }

    // ---- Manual scroll touch API (called on the render/GL thread) -------------

    /**
     * Finger down on the lyric column: take over scrolling from the current position.
     */
    public void scrollDown(float y) {
        userScrollActive = true;
        userDragging = true;
        userFling = false;
        userReturning = false;
        // Start from the column's current position, but inside the scroll bounds so a
        // grab at the song's very end (where the follow centres the last line, past the
        // bottom bound) doesn't jump mid-drag.
        userScroll = clampScroll(lastScrollY);
        userFlingVel = 0f;
        dragSampleCount = 0;
        addDragSample(y);
        userLastInteractNs = System.nanoTime();
    }

    /**
     * Drag: the column follows the finger 1:1 (content moves opposite finger).
     */
    public void scrollMove(float y) {
        if (!userDragging || dragSampleCount == 0) return;
        float prevY = dragSampleY[dragSampleCount - 1];
        userScroll = clampScroll(userScroll - (y - prevY));
        addDragSample(y);
        userLastInteractNs = System.nanoTime();
    }

    /**
     * Release: coast with the windowed release velocity (engine-style inertia).
     */
    public void scrollUp() {
        if (!userDragging) return;
        userDragging = false;
        userFlingVel = computeFlingVel();
        userFling = Math.abs(userFlingVel) > SCROLL_MIN_FLING;
        userScrollLastNs = System.nanoTime();
        userLastInteractNs = userScrollLastNs;
    }

    public void scrollCancel() {
        scrollUp();
    }

    /**
     * Mouse-wheel scroll over the lyric column (desktop only — touch drives
     * {@link #scrollDown}/{@link #scrollMove}/{@link #scrollUp} instead). {@code
     * notches} is in the same wheel-notch units InputBridge already feeds
     * dispatchWheel (NOT pixels — the engine's own Flickable wheel handling
     * multiplies by its internal 48px-per-notch WHEEL_STEP before touching
     * contentY, decompiled from EventDispatcher.wheelOnAxis; mirrored here so a
     * given wheel motion moves the lyric column exactly as far as it would move
     * any QML Flickable-backed list). Settles into the same idle-hold/return-to-
     * follow state a finished drag leaves behind.
     */
    private static final float WHEEL_STEP_PX = 48f;

    public void scrollByWheel(float notches) {
        if (!userScrollActive) {
            userScroll = clampScroll(lastScrollY);
        }
        userScrollActive = true;
        userDragging = false;
        userFling = false;
        userReturning = false;
        // Matches Flickable's own contentY -= step*WHEEL_STEP convention exactly.
        userScroll = clampScroll(userScroll - notches * WHEEL_STEP_PX);
        userLastInteractNs = System.nanoTime();
    }

    /**
     * Start time (ms) of the lyric line under a tapped screen y, or -1 if the tap landed
     * in the blank run-out beyond the first/last line. Uses the last frame's geometry.
     */
    public long timeAtScreenY(float screenY) {
        int n = lines.size();
        if (n == 0 || lineTopsBuf.length < n
                || cachedLineHeights == null || cachedLineHeights.length < n) {
            return -1L;
        }
        float contentY = screenY - lastCenterY + lastScrollY;
        if (contentY < lineTopsBuf[0]
                || contentY >= lineTopsBuf[n - 1] + cachedLineHeights[n - 1]) {
            return -1L;
        }
        int i = lineIndexAt(lineTopsBuf, n, contentY);
        LyricLine line = lines.get(i);
        if (line.syllables.isEmpty()) return -1L;
        return line.syllables.get(0).startMs;
    }

    private void addDragSample(float y) {
        if (dragSampleCount == SCROLL_VEL_SAMPLES) {
            // Left-shift to drop the oldest sample. The overlapping src/dest ranges are
            // safe: System.arraycopy is specified to copy via a temp array when src == dest.
            // noinspection all
            System.arraycopy(dragSampleNs, 1, dragSampleNs, 0, SCROLL_VEL_SAMPLES - 1);
            // noinspection all
            System.arraycopy(dragSampleY, 1, dragSampleY, 0, SCROLL_VEL_SAMPLES - 1);
            dragSampleCount--;
        }
        dragSampleNs[dragSampleCount] = System.nanoTime();
        dragSampleY[dragSampleCount] = y;
        dragSampleCount++;
    }

    // Content velocity (px/s) = -(finger displacement)/(elapsed) across the newest
    // sample back to the oldest within SCROLL_VEL_WINDOW. Same windowed estimate the
    // engine's Flickable uses, so a jittery final sample can't reverse the fling.
    private float computeFlingVel() {
        if (dragSampleCount < 2) return 0f;
        long newest = dragSampleNs[dragSampleCount - 1];
        int oldest = dragSampleCount - 1;
        for (int i = dragSampleCount - 1; i >= 0; i--) {
            if ((newest - dragSampleNs[i]) / 1_000_000_000f > SCROLL_VEL_WINDOW) break;
            oldest = i;
        }
        float dt = (newest - dragSampleNs[oldest]) / 1_000_000_000f;
        if (dt < 0.001f) return 0f;
        return -(dragSampleY[dragSampleCount - 1] - dragSampleY[oldest]) / dt;
    }

    private float clampScroll(float v) {
        if (v < scrollMin) return scrollMin;
        return Math.min(v, scrollMax);
    }

    // The line whose top is at/above a content-space scroll offset (the line sitting at
    // the centre line for that offset) — binary search since lineTops is increasing.
    private static int lineIndexAt(float[] lineTops, int n, float scroll) {
        if (n <= 1) return 0;
        int lo = 0, hi = n - 1, res = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineTops[mid] <= scroll) {
                res = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return res;
    }

    // Advance the user-controlled scroll for this frame and return the column offset.
    // Drag holds; fling coasts under SCROLL_DECEL; once idle past SCROLL_IDLE_RETURN_NS
    // the next line change arms the return, eased back through scrollAnim (the seek
    // spring) so the snap-back matches the progress-adjust motion exactly.
    private float stepUserScroll(float targetScroll, long nowNs, int anchorIdx) {
        if (userDragging) {
            userHoldAnchor = anchorIdx;
            userScrollLastNs = nowNs;
            return userScroll;
        }
        if (userFling) {
            userHoldAnchor = anchorIdx;
            float dt = (nowNs - userScrollLastNs) / 1_000_000_000f;
            userScrollLastNs = nowNs;
            if (dt > 0.05f) dt = 0.05f;
            if (dt > 0f) {
                float next = userScroll + userFlingVel * dt;
                userScroll = clampScroll(next);
                if (userScroll != next) userFlingVel = 0f;   // hit an edge
                else userFlingVel = decayVel(userFlingVel, dt);
                if (Math.abs(userFlingVel) < SCROLL_MIN_FLING) userFling = false;
            }
            return userScroll;
        }
        if (userReturning) {
            float scrollY = (float) scrollAnim.animate(targetScroll);
            if (Math.abs(scrollY - targetScroll) < 0.5f) {
                userReturning = false;
                userScrollActive = false;
            }
            return scrollY;
        }
        // Idle hold: wait until the song moves to a new line, then ease back.
        if ((nowNs - userLastInteractNs) > SCROLL_IDLE_RETURN_NS && anchorIdx != userHoldAnchor) {
            userReturning = true;
            scrollAnim.setValue(userScroll);
            return (float) scrollAnim.animate(targetScroll);
        }
        return userScroll;
    }

    private static float decayVel(float v, float dt) {
        float d = SCROLL_DECEL * dt;
        if (v > 0f) return Math.max(0f, v - d);
        return Math.min(0f, v + d);
    }

    private Shaper shaper() {
        if (harfBuzzShaper == null) harfBuzzShaper = Shaper.makeBestAvailable();
        return harfBuzzShaper;
    }

    private TextLine shapeLine(String text, Font baseFont) {
        // The bundled face covers Latin + Han. For scripts it lacks, preserve the
        // established platform fallback selection, then HarfBuzz shapes the entire
        // visual row with that face instead of switching per timed syllable.
        return shaper().shapeLine(text, fontForText(text, baseFont));
    }

    /** Split only source tokens that cannot fit on a row by themselves. Normal
     * timed syllables remain untouched; the rare oversized token is divided at a
     * locale-aware line boundary, falling back to a grapheme boundary so Thai,
     * Khmer, Lao and other no-space scripts can never become an unwrappable row.
     * Fragment timing is proportional to logical text progress, preserving one
     * continuous karaoke sweep across the original token. */
    private List<Syllable> splitOversizedSyllables(List<Syllable> source, Font font,
                                                   float maxWidth) {
        if (source == null || source.isEmpty() || maxWidth <= 0f) return source;
        ArrayList<Syllable> result = null;
        for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
            Syllable syllable = source.get(sourceIndex);
            List<Syllable> fragments = splitOversizedSyllable(syllable, font, maxWidth);
            if (fragments == null) {
                if (result != null) result.add(syllable);
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(source.size() + fragments.size());
                result.addAll(source.subList(0, sourceIndex));
            }
            result.addAll(fragments);
        }
        return result == null ? source : result;
    }

    /** Returns null when no split is necessary. */
    private List<Syllable> splitOversizedSyllable(Syllable syllable, Font font,
                                                  float maxWidth) {
        String text = syllable.text == null ? "" : syllable.text;
        if (text.isEmpty()) return null;
        try (TextLine line = shapeLine(text, font)) {
            if (line.getWidth() <= maxWidth + 0.5f) return null;

            boolean[] preferred = LyricTextLayout.unicodeLineBreakOffsets(text);
            int[] graphemes = LyricTextLayout.graphemeBoundaries(text);
            boolean usableCaretWidths = false;
            float origin = line.getCoordAtOffset(0);
            for (int i = 1; i + 1 < graphemes.length; i++) {
                if (Math.abs(line.getCoordAtOffset(graphemes[i]) - origin) > 0.01f) {
                    usableCaretWidths = true;
                    break;
                }
            }
            ArrayList<Syllable> out = new ArrayList<>();
            int start = 0;
            while (start < text.length()) {
                int bestPreferred = -1;
                int bestGrapheme = -1;
                for (int boundary : graphemes) {
                    if (boundary <= start) continue;
                    float width;
                    if (usableCaretWidths) {
                        width = Math.abs(line.getCoordAtOffset(boundary)
                                - line.getCoordAtOffset(start));
                    } else {
                        try (TextLine fragment = shapeLine(text.substring(start, boundary), font)) {
                            width = fragment.getWidth();
                        }
                    }
                    if (width <= maxWidth + 0.5f || bestGrapheme < 0) {
                        bestGrapheme = boundary;
                        if (preferred[boundary]) bestPreferred = boundary;
                    } else {
                        break;
                    }
                }
                int end = bestPreferred > start ? bestPreferred : bestGrapheme;
                if (end <= start) {
                    int cp = text.codePointAt(start);
                    end = start + Character.charCount(cp);
                }

                double startProgress = start / (double) text.length();
                double endProgress = end / (double) text.length();
                long fragmentStart = syllable.startMs
                        + Math.round(syllable.durationMs * startProgress);
                long fragmentEnd = end == text.length()
                        ? syllable.startMs + syllable.durationMs
                        : syllable.startMs + Math.round(syllable.durationMs * endProgress);
                out.add(new Syllable(text.substring(start, end), fragmentStart,
                        Math.max(0L, fragmentEnd - fragmentStart)));
                start = end;
            }
            return out.size() <= 1 ? null : out;
        }
    }

    private float[] shapeSyllableAdvances(List<Syllable> syllables, Font font) {
        int n = syllables.size();
        float[] widths = new float[n];
        if (n == 0) return widths;
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            offsets[i] = text.length();
            String s = syllables.get(i).text;
            if (s != null) text.append(s);
        }
        offsets[n] = text.length();
        try (TextLine line = shapeLine(text.toString(), font)) {
            float measuredTotal = 0f;
            int nonZeroAdvances = 0;
            for (int i = 0; i < n; i++) {
                widths[i] = Math.abs(
                        line.getCoordAtOffset(offsets[i + 1]) - line.getCoordAtOffset(offsets[i]));
                measuredTotal += widths[i];
                if (widths[i] > 0.01f) nonZeroAdvances++;
            }
            float tolerance = Math.max(1f, line.getWidth() * 0.02f);
            boolean collapsedCarets = n > 1 && nonZeroAdvances <= 1 && line.getWidth() > 0.01f;
            if (collapsedCarets || Math.abs(measuredTotal - line.getWidth()) > tolerance) {
                for (int i = 0; i < n; i++) {
                    String value = syllables.get(i).text;
                    try (TextLine segment = shapeLine(value == null ? "" : value, font)) {
                        widths[i] = segment.getWidth();
                    }
                }
            }
        }
        return widths;
    }

    private ShapedRow shapeMainRow(List<Syllable> syllables, int from, int to, Font font) {
        int n = Math.max(0, to - from);
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            offsets[i] = text.length();
            String s = syllables.get(from + i).text;
            if (s != null) text.append(s);
        }
        offsets[n] = text.length();

        try (TextLine line = shapeLine(text.toString(), font)) {
            float[] x = new float[n + 1];
            for (int i = 0; i <= n; i++) x[i] = line.getCoordAtOffset(offsets[i]);
            int visibleOffset = 0;
            while (visibleOffset < text.length()) {
                int cp = text.codePointAt(visibleOffset);
                if (!Character.isWhitespace(cp)) break;
                visibleOffset += Character.charCount(cp);
            }
            WordSpan[] words = buildWordSpans(text.toString(), offsets, line);
            return new ShapedRow(from, to, line.getTextBlob(), line.getWidth(),
                    line.getCoordAtOffset(visibleOffset), x, words);
        }
    }

    private static WordSpan[] buildWordSpans(String text, int[] syllableOffsets, TextLine line) {
        int[][] ranges = LyricTextLayout.displayWordSyllableRanges(text, syllableOffsets);
        WordSpan[] words = new WordSpan[ranges.length];
        for (int w = 0; w < ranges.length; w++) {
            int start = ranges[w][0];
            int end = ranges[w][1];
            int first = ranges[w][2];
            int last = ranges[w][3];
            words[w] = new WordSpan(first, last, start, end,
                    line.getCoordAtOffset(start), line.getCoordAtOffset(end));
        }
        return words;
    }

    private ShapedText shapeText(String text, Font font) {
        try (TextLine line = shapeLine(text, font)) {
            return new ShapedText(line.getTextBlob(), line.getWidth());
        }
    }

    /** HarfBuzz-aware wrap for translation/romaji. The source is shaped once to
     * locate breaks, then only the final visual rows are shaped and cached. */
    private ShapedText[] shapeWrappedText(String text, Font font, float maxWidth) {
        if (text == null || text.isEmpty()) return new ShapedText[]{shapeText("", font)};
        ArrayList<ShapedText> rows = new ArrayList<>();
        try (TextLine full = shapeLine(text, font)) {
            int start = 0;
            while (start < text.length()) {
                float startX = full.getCoordAtOffset(start);
                int best = start;
                int bestBreak = -1;
                int p = start;
                while (p < text.length()) {
                    int cp = text.codePointAt(p);
                    int next = p + Character.charCount(cp);
                    if (Character.isWhitespace(cp)) bestBreak = p;
                    if (full.getCoordAtOffset(next) - startX > maxWidth && p > start) break;
                    best = next;
                    p = next;
                }
                int end = best;
                int nextStart = best;
                if (p < text.length() && bestBreak > start) {
                    end = bestBreak;
                    nextStart = bestBreak;
                    while (nextStart < text.length()
                            && Character.isWhitespace(text.codePointAt(nextStart))) {
                        nextStart += Character.charCount(text.codePointAt(nextStart));
                    }
                }
                if (end <= start) {
                    end = Math.min(text.length(), start + Character.charCount(text.codePointAt(start)));
                }
                rows.add(shapeText(text.substring(start, end), font));
                start = Math.max(end, nextStart);
            }
        }
        return rows.toArray(new ShapedText[0]);
    }

    private void drawSubLine(ShapedText text, float leftX, float rightAnchorX, float y,
                             float alpha, boolean alignRight, boolean shadowOn) {
        if (text == null || text.blob == null) return;
        float x = alignRight ? rightAnchorX - text.width : leftX;
        if (shadowOn) drawTextShadow(text.blob, x, y, alpha);
        Paint paint = LyricSkia.scratchPaint();
        paint.setColor(0xFFE6E6E6);
        paint.setAlphaf(alpha);
        paint.setAntiAlias(true);
        LyricSkia.getCanvas().drawTextBlob(text.blob, x, y, paint);
    }

    private int findActiveGroup(long pos) {
        return LyricTimeline.activeGroupIndex(groups, pos);
    }

    /**
     * Draw a sub-row, AMLL fidelity port.
     *
     * <p>Three things happen per row:
     * <ol>
     *   <li><b>Per-syllable lift</b> (AMLL {@code initFloatAnimation}).
     *       {@code translateY 0 → -0.05em} with {@code ease-out}, over
     *       {@code max(1000ms, sylDur)}. Stays at peak afterwards
     *       (fill-forwards). Scaled by {@code activeK} so the lift
     *       smoothly returns to 0 during the group's fade-out.</li>
     *   <li><b>Row-wide alpha sweep</b> (AMLL {@code mask-image} +
     *       animated {@code mask-position}). A horizontal gradient inside
     *       a saveLayer: bright on the left of the sweep head, dim
     *       ({@code DARK_MASK_ALPHA = 0.2}) on the right, blending over
     *       {@code SWEEP_FADE_PX}. The head moves between syllable left
     *       edges in proportion to time, so a row reads as a horizontal
     *       progress fill rather than per-syllable alpha steps.</li>
     *   <li><b>SaveLayer composite alpha</b>. The whole layer is blended
     *       back with {@code baseAlpha}, which the caller already faded
     *       between idle (0.42) and active (1.0) using {@code activeK}.
     *       So a finishing group's row dims smoothly without the mask
     *       sweep snapping anything.</li>
     * </ol>
     *
     * <p>{@code activeK ≤ 0.001} short-circuits to one flat TextBlob draw —
     * past the group's fade-out window, no animation work is needed.
     */
    private void drawShapedRow(List<Syllable> syllables, ShapedRow row,
                                   float startX, float baselineY,
                                   float ascent, float descent, long pos,
                                   float baseAlpha, float activeK, boolean enableLift, boolean spring,
                                   boolean glowOn, boolean shadowOn) {
        if (row == null || row.blob == null || row.from >= row.to || row.width <= 0f) return;
        Canvas canvas = LyricSkia.getCanvas();

        if (activeK <= 0.001f) {
            Paint paint = LyricSkia.scratchPaint();
            paint.setColor(0xFFFFFFFF);
            paint.setAlphaf(baseAlpha);
            paint.setAntiAlias(true);
            if (shadowOn) drawTextShadow(row.blob, startX, baselineY, baseAlpha);
            canvas.drawTextBlob(row.blob, startX, baselineY, paint);
            return;
        }

        // Plain LRC with synthetic per-token animation disabled has neither a
        // karaoke sweep nor word glow. Drawing its immutable HarfBuzz blob directly
        // avoids rasterising a 2x texture and building a RuntimeEffect every frame.
        if (!enableLift) {
            Paint paint = LyricSkia.scratchPaint();
            paint.setColor(0xFFFFFFFF);
            paint.setAlphaf(baseAlpha);
            paint.setAntiAlias(true);
            if (shadowOn) drawTextShadow(row.blob, startX, baselineY, baseAlpha);
            canvas.drawTextBlob(row.blob, startX, baselineY, paint);
            return;
        }

        int n = row.to - row.from;
        if (sylLeftBuf.length < n + 1) sylLeftBuf = new float[n + 1];
        float[] sylLeft = sylLeftBuf;
        for (int i = 0; i <= n; i++) sylLeft[i] = startX + row.syllableX[i];
        float rowRightX = startX + row.width;
        float sweepX = enableLift
                ? computeSweepX(syllables, row.from, row.to, sylLeft, pos) : 0f;

        if (liftBuf.length < n) liftBuf = new float[n];
        for (int s = 0; s < n; s++) {
            float k = syllableAnimationK(syllables.get(row.from + s), pos, spring);
            float lift = enableLift ? -LIFT_PEAK_PX * k * activeK : 0f;
            liftBuf[s] = lift;
        }

        Paint layerPaint = lyricLayerPaint;
        layerPaint.setAlphaf(baseAlpha);
        canvas.saveLayer(
                startX - 8f,
                baselineY + ascent - MAX_SHADER_LIFT_PX - 8f,
                rowRightX + 8f,
                baselineY + descent + 8f,
                layerPaint);
        try {
            ensureHighResRaster(row, ascent, descent, shadowOn);
            long liftedShader = makeLiftShader(row, startX, baselineY, rowRightX, n,
                    syllables, pos, sweepX, activeK, glowOn, shadowOn);
            try {
                Paint textPaint = LyricSkia.scratchPaint();
                setShader(textPaint, liftedShader);
                try {
                    textPaint.setAlphaf(1f);
                    textPaint.setAntiAlias(true);
                    drawRect(canvas,
                            startX + row.rasterLeft,
                            baselineY + row.rasterTop - MAX_SHADER_LIFT_PX,
                            startX + row.rasterLeft + row.rasterWidth,
                            baselineY + row.rasterTop + row.rasterHeight, textPaint);
                } finally {
                    setShader(textPaint, 0L);
                }
                // Apply the karaoke sweep to the base text first. The word glow is
                // deliberately composited afterwards: masking it with the sweep made
                // the unsung half dark, so an "entire word" glow still looked like a
                // tail-only glow while the play head was inside a split word.
                if (enableLift) {
                    float maskDark = 1f - (1f - DARK_MASK_ALPHA) * activeK;
                    applySweepMask(canvas, sweepX, maskDark);
                }
                drawWordGlows(canvas, syllables, row, startX, baselineY,
                        ascent, descent, pos, activeK, glowOn, shadowOn, liftedShader);
            } finally {
                // RuntimeEffectBuilder returns one owned sk_sp. Paint temporarily
                // refs it while drawing; after all paints are cleared, release the
                // original ref without constructing a Java Shader/Cleaner wrapper.
                Managed._nInvokeFinalizer(RefCnt._FinalizerHolder.PTR, liftedShader);
            }
        } finally {
            canvas.restore();
        }
    }

    private float syllableAnimationK(Syllable syl, long pos, boolean spring) {
        long start = syl.startMs;
        if (spring) return liftSpringK((pos - start) / 1000.0);
        long duration = Math.max(LIFT_MIN_DURATION_MS, Math.max(0L, syl.durationMs));
        float t = pos <= start ? 0f : pos >= start + duration
                ? 1f : (pos - start) / (float) duration;
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    /** Rasterize the immutable HarfBuzz row at 2x once, then retain only a small
     * LRU window. Playback shaders sample this source instead of the 1x saveLayer
     * bitmap, so fractional lift and emphasis scaling keep the stem detail. */
    private void ensureHighResRaster(ShapedRow row, float ascent, float descent,
                                     boolean shadowOn) {
        if (row.highResImage != null && row.rasterWithShadow == shadowOn) {
            highResRowLru.remove(row);
            highResRowLru.addLast(row);
            return;
        }
        highResRowLru.remove(row);
        row.closeRaster();

        row.rasterLeft = -TEXT_RASTER_PAD;
        row.rasterTop = ascent - TEXT_RASTER_PAD;
        row.rasterWidth = Math.max(1f, row.width + TEXT_RASTER_PAD * 2f);
        row.rasterHeight = Math.max(1f, descent - ascent + TEXT_RASTER_PAD * 2f);
        int pixelWidth = Math.max(1, (int) Math.ceil(row.rasterWidth * TEXT_SUPERSAMPLE));
        int pixelHeight = Math.max(1, (int) Math.ceil(row.rasterHeight * TEXT_SUPERSAMPLE));
        try (Surface surface = Surface.makeRasterN32Premul(pixelWidth, pixelHeight)) {
            Canvas raster = surface.getCanvas();
            raster.clear(0x00000000);
            raster.scale(TEXT_SUPERSAMPLE, TEXT_SUPERSAMPLE);
            float x = -row.rasterLeft;
            float baseline = -row.rasterTop;
            if (shadowOn) {
                textShadowPaint.setAlphaf(TEXT_SHADOW_ALPHA);
                raster.drawTextBlob(row.blob, x, baseline + TEXT_SHADOW_OFFSET_Y, textShadowPaint);
            }
            Paint glyphPaint = LyricSkia.scratchPaint();
            glyphPaint.setColor(0xFFFFFFFF);
            glyphPaint.setAlphaf(1f);
            glyphPaint.setAntiAlias(true);
            raster.drawTextBlob(row.blob, x, baseline, glyphPaint);
            row.highResImage = surface.makeImageSnapshot();
        }
        row.highResImageShader = row.highResImage.makeShader(
                FilterTileMode.DECAL, FilterTileMode.DECAL,
                SamplingMode.LINEAR, Matrix33.IDENTITY);
        row.rasterWithShadow = shadowOn;
        highResRowLru.addLast(row);
        while (highResRowLru.size() > MAX_HIGH_RES_ROWS) {
            ShapedRow evicted = highResRowLru.removeFirst();
            if (evicted != row) evicted.closeRaster();
        }
    }

    /** Build a cheap per-frame shader over the cached 2x row texture. HarfBuzz
     * geometry and source pixels are both immutable; only lift uniforms change. */
    private long makeLiftShader(ShapedRow row, float startX, float baselineY,
                                float rowRightX, int n, List<Syllable> syllables,
                                long pos, float sweepX, float activeK, boolean glowOn,
                                boolean shadowOn) {
        int count = Math.min(n, MAX_LIFT_SEGMENTS);
        java.util.Arrays.fill(liftUniformBuf, 0f);
        for (int i = 0; i < count; i++) {
            int p = i * 4;
            liftUniformBuf[p] = i == 0
                    ? startX - 8f : startX + row.syllableX[i];
            liftUniformBuf[p + 1] = i == count - 1
                    ? rowRightX + 8f : startX + row.syllableX[i + 1];
            liftUniformBuf[p + 2] = liftBuf[i];
        }

        java.util.Arrays.fill(wordLiftUniformBuf, 0f);
        int wordLiftCount = 0;
        // The ribbon lift stays gated behind shadowOn + the duration floor even
        // though the glow highlight itself (drawWordGlows below) doesn't, when
        // shadow is off: applying it to every word regardless of duration reads
        // as each character fluttering up like loose paper, not a highlight.
        if (wordGlowSupported && glowOn && shadowOn) {
            for (WordSpan word : row.words) {
                if (wordLiftCount >= MAX_WORD_LIFT_SEGMENTS) break;
                Syllable first = syllables.get(row.from + word.firstSyllable);
                Syllable last = syllables.get(row.from + word.lastSyllable);
                long end = last.startMs + Math.max(0L, last.durationMs);
                if (end - first.startMs < WORD_GLOW_MIN_DURATION_MS) continue;
                if (pos < first.startMs || pos > end) continue;
                int p = wordLiftCount * 4;
                float wordX0 = startX + Math.min(word.x0, word.x1);
                float wordX1 = startX + Math.max(word.x0, word.x1);
                // Use the exact karaoke sweep head rather than a separate clock.
                // This preserves pauses and unevenly timed split syllables: when
                // the highlight holds at a boundary, the ribbon holds there too.
                float lyricProgress = Math.max(0f, Math.min(1f,
                        (sweepX - wordX0) / Math.max(0.001f, wordX1 - wordX0)));
                float amount = -WORD_RIBBON_LIFT_PX
                        * (float) Math.sin(Math.PI * lyricProgress) * activeK;
                if (Math.abs(amount) <= 0.001f) continue;
                wordLiftUniformBuf[p] = wordX0;
                wordLiftUniformBuf[p + 1] = wordX1;
                wordLiftUniformBuf[p + 2] = amount;
                wordLiftUniformBuf[p + 3] = lyricProgress;
                wordLiftCount++;
            }
        }
        RuntimeEffectBuilder builder = liftBuilder();
        builder.setUniform("segments", liftUniformBuf);
        builder.setUniform("segmentCount", count);
        builder.setUniform("wordLifts", wordLiftUniformBuf);
        builder.setUniform("wordLiftCount", wordLiftCount);
        builder.setUniform("sourceOrigin",
                startX + row.rasterLeft, baselineY + row.rasterTop);
        builder.setUniform("sourceScale", TEXT_SUPERSAMPLE);
        builder.setChild("content", row.highResImageShader);
        return RuntimeEffectBuilder._nMakeShader(Native.getPtr(builder), null);
    }

    /** Every eligible display word contributes its complete shaped range to the
     * glow layer. Word grouping never changes the independently timed base lift. */
    private void drawWordGlows(Canvas canvas, List<Syllable> syllables, ShapedRow row,
                               float startX, float baselineY, float ascent, float descent,
                               long pos, float activeK, boolean glowOn, boolean shadowOn,
                               long liftedShader) {
        if (!wordGlowSupported || !glowOn || row.words.length == 0) return;
        boolean glowLayerSaved = false;
        try {
            for (WordSpan word : row.words) {
                Syllable first = syllables.get(row.from + word.firstSyllable);
                Syllable last = syllables.get(row.from + word.lastSyllable);
                long wordEnd = last.startMs + Math.max(0L, last.durationMs);
                long wordDuration = wordEnd - first.startMs;
                if (shadowOn && wordDuration < WORD_GLOW_MIN_DURATION_MS) continue;
                if (pos < first.startMs || pos > wordEnd) continue;
                float wordProgress = (pos - first.startMs)
                        / (float) Math.max(1L, wordDuration);
                // The old glow was fill-forwards, which made every completed word
                // accumulate until the complete line looked illuminated. Fade only
                // in the very last 10% so the active word stays bright through its
                // sustained note, then hands the glow to the next word smoothly.
                float attack = smoothstep(0f, 0.18f, wordProgress);
                float release = 1f - smoothstep(0.90f, 1f, wordProgress);
                float alpha = activeK * attack * release * GLOW_ALPHA;
                if (alpha <= 0.01f) continue;

                if (!glowLayerSaved) {
                    canvas.saveLayer(startX - 8f,
                            baselineY + ascent - MAX_SHADER_LIFT_PX - 8f,
                            startX + row.width + 8f, baselineY + descent + 8f, glowLayerPaint);
                    glowLayerSaved = true;
                }

                // Clip the INPUT glyphs to the complete display word before the blur
                // layer is restored. For en+dure this is one continuous HarfBuzz range.
                float x0 = startX + Math.min(word.x0, word.x1) - 1f;
                float x1 = startX + Math.max(word.x0, word.x1) + 1f;
                canvas.save();
                try {
                    clipRect(canvas, x0,
                            baselineY + ascent - MAX_SHADER_LIFT_PX,
                            x1, baselineY + descent + 2f);
                    setShader(glowGlyphPaint, liftedShader);
                    glowGlyphPaint.setAlphaf(alpha);
                    drawRect(canvas,
                            startX + row.rasterLeft,
                            baselineY + row.rasterTop - MAX_SHADER_LIFT_PX,
                            startX + row.rasterLeft + row.rasterWidth,
                            baselineY + row.rasterTop + row.rasterHeight, glowGlyphPaint);
                } finally {
                    setShader(glowGlyphPaint, 0L);
                    canvas.restore();
                }
            }
        } finally {
            if (glowLayerSaved) canvas.restore();
        }
    }

    // Skija's Rect factories allocate a Java wrapper. These paths run for every
    // animated row (and every glowing word), so use the public native primitives.
    private static void drawRect(Canvas canvas, float left, float top,
                                 float right, float bottom, Paint paint) {
        Canvas._nDrawRect(Native.getPtr(canvas), left, top, right, bottom,
                Native.getPtr(paint));
    }

    private static void clipRect(Canvas canvas, float left, float top,
                                 float right, float bottom) {
        Canvas._nClipRect(Native.getPtr(canvas), left, top, right, bottom,
                ClipMode.INTERSECT.ordinal(), false);
    }

    private static void setShader(Paint paint, long shader) {
        Paint._nSetShader(Native.getPtr(paint), shader);
    }

    private RuntimeEffect liftEffect() {
        if (liftEffect == null) liftEffect = compileShaderResource(LIFT_SHADER_RESOURCE, "lyric lift");
        return liftEffect;
    }

    private RuntimeEffectBuilder liftBuilder() {
        if (liftBuilder == null) liftBuilder = new RuntimeEffectBuilder(liftEffect());
        return liftBuilder;
    }

    private static RuntimeEffect compileShaderResource(String path, String label) {
        try (InputStream in = LyricRenderer.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("resource not found: " + path);
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            StringBuilder source = new StringBuilder(4096);
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) source.append(buffer, 0, read);
            return RuntimeEffect.makeForShader(source.toString());
        } catch (Throwable t) {
            dev.t1m3.qplayer.util.Logger.warn("{} shader compile failed: {}", label, t.getMessage());
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new IllegalStateException("Failed to load " + label + " shader", t);
        }
    }

    private void drawTextShadow(TextBlob blob, float x, float baselineY, float alpha) {
        textShadowPaint.setAlphaf(alpha * TEXT_SHADOW_ALPHA);
        LyricSkia.getCanvas().drawTextBlob(blob, x, baselineY + TEXT_SHADOW_OFFSET_Y,
                textShadowPaint);
    }

    /**
     * Park sweepX outside the row before/after the line plays so the mask
     * presents a stable bright (post-finish) or dark (pre-start) state.
     * During playback, sweepX lerps across each syllable's own width by
     * {@code (pos - sStart) / (sStart + duration - sStart)}, then holds at
     * the next syllable's left edge through any gap before it starts.
     */
    private static float computeSweepX(List<Syllable> syllables, int from, int to,
                                       float[] sylLeft, long pos) {
        int n = to - from;
        long firstStart = syllables.get(from).startMs;
        Syllable last = syllables.get(to - 1);
        long lastEnd = last.startMs + Math.max(0L, last.durationMs);

        // Before the line: park way left so the mask is fully dark.
        if (pos < firstStart) return sylLeft[0] - SWEEP_FADE_PX * 2f;
        // After the line: park way right so the mask is fully bright.
        if (pos >= lastEnd) return sylLeft[n] + SWEEP_FADE_PX * 2f;

        for (int s = 0; s < n; s++) {
            Syllable syl = syllables.get(from + s);
            long sStart = syl.startMs;
            // Finish a syllable at its OWN end, not the next one's start. Stretching
            // the fill to the next start kept the head creeping through the silence
            // after the word was already sung, so the sweep visibly trailed the
            // vocal. Zero-duration tokens (QRC's word separators) have no span of
            // their own and still bridge to the next syllable's start.
            long sEnd = syl.durationMs > 0L
                    ? sStart + syl.durationMs
                    : ((s + 1 < n) ? syllables.get(from + s + 1).startMs : sStart);
            if (sEnd <= sStart) sEnd = sStart + 1L;
            if (pos < sStart) return sylLeft[s];
            if (pos < sEnd) {
                float frac = (pos - sStart) / (float) (sEnd - sStart);
                float w = sylLeft[s + 1] - sylLeft[s];
                return sylLeft[s] + w * frac;
            }
        }
        return sylLeft[n] + SWEEP_FADE_PX * 2f;
    }

    /**
     * AMLL's mask-image gradient as a DST_IN draw on the current saveLayer: bright
     * (1.0) on the sung side, dark ({@code maskDark}) on the unsung side, blending
     * over {@code SWEEP_FADE_PX} at the head.
     *
     * <p>The gradient SHADER is built once for a fixed [0, SWEEP_FADE_PX] band
     * (CLAMP, so it's bright to the left and dark to the right) and reused — only
     * its dark colour changes (with activeK during the enter/exit fade), so it's
     * rebuilt only then, not every frame. Each frame the head is positioned by
     * translating the canvas, and a cached oversized rect is filled, so a steady
     * sweep allocates nothing (the old per-frame {@code makeLinearGradient} +
     * bounds Rect was the active row's residual GC churn).
     */
    private void applySweepMask(Canvas canvas, float sweepX, float maskDark) {
        if (sweepShader == null || sweepShaderDark != maskDark) {
            if (sweepShader != null) sweepShader.close();
            int dark = ((int) (maskDark * 255f) << 24) | 0x00FFFFFF;
            sweepColors[0] = 0xFFFFFFFF;
            sweepColors[1] = dark;
            sweepStops[0] = 0f;
            sweepStops[1] = 1f;
            sweepShader = Shader.makeLinearGradient(0f, 0f, SWEEP_FADE_PX, 0f, sweepColors, sweepStops);
            sweepShaderDark = maskDark;
        }
        sweepPaint.setShader(sweepShader);
        sweepPaint.setBlendMode(BlendMode.DST_IN);
        canvas.save();
        canvas.translate(sweepX - SWEEP_FADE_PX * 0.5f, 0f);
        canvas.drawRect(sweepBigRect, sweepPaint);
        canvas.restore();
        sweepPaint.setShader(null);
    }

    /**
     * Underdamped step response of Apple's liftSpring (mass 1,
     * damping 7). {@code tau} is elapsed seconds since the syl
     * returns ~0 at 0, settles toward 1 (fill-forwards) within
     * overshoot. Negative tau (syllable not started) → 0.
     * <p>
     * Integrate one line's scroll spring toward {@code target} over {@code dt}
     * seconds with the given (AMLL-derived) stiffness/damping, sub-stepping for
     * stiff-spring stability. Mirrors {@link SpringAnim} on the per-line arrays.
     */
    private void stepLineSpring(int i, float target, double dt, double stiffness, double damping) {
        double value = lineCurTop[i];
        double vel = lineVelTop[i];
        int steps = 1 + (int) (dt / 0.008);
        double sub = dt / steps;
        for (int s = 0; s < steps; s++) {
            double a = -stiffness * (value - target) - damping * vel;
            vel += a * sub;
            value += vel * sub;
        }
        if (Math.abs(vel) < 0.01 && Math.abs(value - target) < 0.05) {
            value = target;
            vel = 0.0;
        }
        lineCurTop[i] = (float) value;
        lineVelTop[i] = (float) vel;
    }

    private static float liftSpringK(double tau) {
        if (tau <= 0.0) return 0f;
        double zw = LIFT_ZETA * LIFT_OMEGA0;
        double wd = LIFT_OMEGA0 * Math.sqrt(1.0 - LIFT_ZETA * LIFT_ZETA);
        double env = Math.exp(-zw * tau);
        double y = 1.0 - env * (Math.cos(wd * tau) + (zw / wd) * Math.sin(wd * tau));
        if (y < 0.0) y = 0.0;
        return (float) y;
    }

    /**
     * GLSL-style smoothstep: 0 below {@code a}, 1 above {@code b}, smooth in between.
     */
    private static float smoothstep(float a, float b, float x) {
        if (b <= a) return x < a ? 0f : 1f;
        float t = (x - a) / (b - a);
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    /**
     * BG line scale curve. The row trails the main line by
     * {@link #BG_POP_IN_DELAY_MS}, then pops in over {@link #BG_POP_IN_MS} with a
     * small easeOutBack overshoot (a gentle bounce that settles back to 1). Pop-out
     * stays a plain smoothstep collapse over {@link #BG_POP_OUT_MS}.
     */
    private static float computeBgScaleK(long positionMs, LyricTimeline.Group g) {
        long popStart = g.startMs + BG_POP_IN_DELAY_MS;
        if (positionMs < popStart) return 0f;
        if (positionMs < popStart + BG_POP_IN_MS) {
            float k = (positionMs - popStart) / (float) BG_POP_IN_MS;
            return easeOutBackSmall(k);
        }
        if (positionMs < g.endMs) return 1f;
        float dt = (positionMs - g.endMs) / (float) BG_POP_OUT_MS;
        if (dt >= 1f) return 0f;
        return 1f - smoothstep(0f, 1f, dt);
    }

    // easeOutBack: rises past 1 then recoils back to it. Over the longer
    // BG_POP_IN_MS window this reads as a slow, visible bounce-back (the
    // "回弹" the user wants) rather than a quick twitch.
    private static float easeOutBackSmall(float x) {
        float c1 = 1.7f;
        float c3 = c1 + 1f;
        float t = x - 1f;
        return 1f + c3 * t * t * t + c1 * t * t;
    }

    /**
     * Group activation curve. 0 before the delayed fade-in window,
     * smoothsteps to 1 over {@code ACTIVE_FADE_IN_MS}, holds 1 across the active
     * window through {@code endMs}, holds for {@code ACTIVE_FADE_OUT_DELAY_MS},
     * then smoothsteps back to 0 over {@code ACTIVE_FADE_OUT_MS}.
     * Used both at render time (alpha/lift/scale)
     * and at layout time (BG lineHeight collapse).
     */
    private static float computeActiveK(long positionMs, LyricTimeline.Group g) {
        long fadeInStart = fadeInStartMs(g);
        long fadeInEnd = fadeInStart + ACTIVE_FADE_IN_MS;
        if (positionMs < fadeInStart) return 0f;
        if (positionMs < fadeInEnd) {
            float dt = (positionMs - fadeInStart) / (float) ACTIVE_FADE_IN_MS;
            return smoothstep(0f, 1f, dt);
        }
        long fadeOutStart = g.endMs + ACTIVE_FADE_OUT_DELAY_MS;
        long fadeOutEnd = fadeOutStart + ACTIVE_FADE_OUT_MS;
        if (positionMs < fadeOutStart) return 1f;
        if (positionMs >= fadeOutEnd) return 0f;
        float dt = (positionMs - fadeOutStart) / (float) Math.max(1L, fadeOutEnd - fadeOutStart);
        return 1f - smoothstep(0f, 1f, dt);
    }

    private static long fadeInStartMs(LyricTimeline.Group g) {
        return g.startMs - ACTIVE_FADE_IN_MS + ACTIVE_FADE_IN_DELAY_MS;
    }
}
