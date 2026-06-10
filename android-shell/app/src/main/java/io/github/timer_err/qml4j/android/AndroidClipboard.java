package io.github.timer_err.qml4j.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import io.github.timer_err.qml4j.render.Clipboard;

final class AndroidClipboard implements Clipboard {

    private final Context appContext;

    AndroidClipboard(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    @Override
    public String getText() {
        ClipboardManager cm = manager();
        if (cm == null) return null;
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return null;
        CharSequence cs = cd.getItemAt(0).coerceToText(appContext);
        return cs == null ? null : cs.toString();
    }

    @Override
    public void setText(String text) {
        ClipboardManager cm = manager();
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("text", text == null ? "" : text));
    }

    private ClipboardManager manager() {
        return (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
    }
}
