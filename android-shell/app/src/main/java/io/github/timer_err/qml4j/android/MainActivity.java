package io.github.timer_err.qml4j.android;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {

    static {
        // Skija's auto-loader detects os.name=Linux, arch=aarch64 and looks
        // for the .so as a JAR resource at io/github/humbleui/skija/linux/arm64/.
        // We ship it via jniLibs/arm64-v8a/ instead, so bypass the auto-loader
        // and load via System.loadLibrary ourselves.
        System.setProperty("skija.staticLoad", "false");
        System.loadLibrary("skija");
        // CRITICAL: Skija caches all jclass/jmethodID/jfieldID in native
        // _nAfterLoad(). Library.load() normally calls it, but we bypass that
        // with our own System.loadLibrary, so call it explicitly. Without this,
        // every native method that constructs/reads a Java object (measureText
        // -> Rect, getMetrics -> FontMetrics, Shaper) dereferences a NULL ID and
        // crashes natively; only primitive-returning calls work.
        io.github.humbleui.skija.impl.Library._nAfterLoad();
        // Pre-warm Skija classes so any JNI FindClass / class-ref caching
        // happens with the app classloader visible on the stack.
        try {
            Class.forName("io.github.humbleui.skija.ImageInfo");
            Class.forName("io.github.humbleui.skija.ColorInfo");
            Class.forName("io.github.humbleui.skija.ColorSpace");
            Class.forName("io.github.humbleui.skija.Color4f");
            Class.forName("io.github.humbleui.skija.Image");
            Class.forName("io.github.humbleui.skija.Canvas");
            Class.forName("io.github.humbleui.skija.Paint");
            Class.forName("io.github.humbleui.skija.Font");
            Class.forName("io.github.humbleui.types.Rect");
            Class.forName("io.github.humbleui.types.IRect");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private QmlGLSurfaceView glView;

    private static final class Page {
        final String title; final String file; final float scale;
        Page(String title, String file, float scale) { this.title = title; this.file = file; this.scale = scale; }
    }

    private Page[] pages() {
        float d = getResources().getDisplayMetrics().density;
        return new Page[]{
            new Page("Legacy demo (1×)", "demo.qml", 1f),
            new Page("ScrollBar", "showcases/ScrollBarShowcase.qml", d),
            new Page("ToolTip", "showcases/ToolTipShowcase.qml", d),
            new Page("Layouts", "showcases/LayoutShowcase.qml", d),
            new Page("Qt.color", "showcases/ColorShowcase.qml", d),
            new Page("default property", "showcases/DefaultPropShowcase.qml", d),
            new Page("Checkbox", "showcases/CheckboxShowcase.qml", d),
            new Page("Switch / RadioButton", "showcases/SwitchRadioShowcase.qml", d),
            new Page("IconButton", "showcases/IconButtonShowcase.qml", d),
            new Page("Card", "showcases/CardShowcase.qml", d),
            new Page("FAB", "showcases/FabShowcase.qml", d),
            new Page("Chip", "showcases/ChipShowcase.qml", d),
            new Page("Button", "showcases/ButtonShowcase.qml", d),
            new Page("Dialog", "showcases/DialogShowcase.qml", d),
            new Page("Slider", "showcases/SliderShowcase.qml", d),
            new Page("NavigationBar", "showcases/NavigationBarShowcase.qml", d),
            new Page("SegmentedButton", "showcases/SegmentedButtonShowcase.qml", d),
            new Page("Snackbar", "showcases/SnackbarShowcase.qml", d),
            new Page("MD3 Gallery", "showcases/Md3GalleryShowcase.qml", d),
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showLauncher();
    }

    private void showLauncher() {
        if (glView != null) { glView.onPause(); glView = null; }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(24, 24, 24, 24);
        for (final Page p : pages()) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(p.title);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openPage(p); }
            });
            list.addView(b);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        setContentView(sv);
    }

    private void openPage(Page p) {
        String qml;
        try {
            qml = readAsset(p.file);
        } catch (IOException e) {
            throw new RuntimeException("failed to read asset: " + p.file, e);
        }
        QmlEngine engine = new QmlEngine(
            new DexClassLoaderBackend(getClass().getClassLoader()));
        glView = new QmlGLSurfaceView(this, engine, qml,
            new AssetResourceLoader(getAssets()), p.scale);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        glView.setLayoutParams(glLp);
        root.addView(glView);
        root.addView(buildKeyBar());
        setContentView(root);
    }

    @Override
    public void onBackPressed() {
        if (glView != null) { showLauncher(); return; }
        super.onBackPressed();
    }

    private LinearLayout buildKeyBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        addKeyButton(bar, "A", 65, "a");
        addKeyButton(bar, "Spc", 32, " ");
        addKeyButton(bar, "Ent", QmlView.KEY_ENTER, null);
        addKeyButton(bar, "Esc", QmlView.KEY_ESCAPE, null);
        addKeyButton(bar, "<", QmlView.KEY_LEFT, null);
        addKeyButton(bar, ">", QmlView.KEY_RIGHT, null);
        addKeyButton(bar, "Up", QmlView.KEY_UP, null);
        addKeyButton(bar, "Dn", QmlView.KEY_DOWN, null);
        addKeyButton(bar, "Tab", QmlView.KEY_TAB, null);
        addKeyButton(bar, "BTab", QmlView.KEY_BACKTAB, null);
        return bar;
    }

    private void addKeyButton(LinearLayout bar, String label, final int code, final String text) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setMaxLines(1);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { glView.sendSyntheticKey(code, text); }
        });
        bar.addView(b);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glView != null) glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
