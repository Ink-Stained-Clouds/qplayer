package io.github.timer_err.qml4j.android;

import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;

import io.github.timer_err.qml4j.render.items.input.TextEditable;

final class QmlInputConnection extends BaseInputConnection {

    private final QmlGLSurfaceView view;
    /** Length of the text currently published through setComposingText().
     *  Handwriting IMEs often never send key events: they repeatedly replace a
     *  composing candidate and only finish composition at the end. */
    private int composingLength;

    QmlInputConnection(QmlGLSurfaceView view, boolean fullEditor) {
        super(view, fullEditor);
        this.view = view;
    }

    private TextEditable focusedTextEditable() {
        if (view.qmlView() == null) return null;
        Object f = view.qmlView().focused();
        return f instanceof TextEditable ? (TextEditable) f : null;
    }

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags) {
        TextEditable ti = focusedTextEditable();
        if (ti == null) return "";
        String s = ti.text(); if (s == null) s = "";
        int pos = clamp(ti.cursorPosition(), s.length());
        int start = Math.max(0, pos - n);
        return s.substring(start, pos);
    }

    @Override
    public CharSequence getTextAfterCursor(int n, int flags) {
        TextEditable ti = focusedTextEditable();
        if (ti == null) return "";
        String s = ti.text(); if (s == null) s = "";
        int pos = clamp(ti.cursorPosition(), s.length());
        int end = Math.min(s.length(), pos + n);
        return s.substring(pos, end);
    }

    @Override
    public CharSequence getSelectedText(int flags) {
        return null;
    }

    @Override
    public int getCursorCapsMode(int reqModes) {
        return 0;
    }

    private static int clamp(int p, int len) {
        if (p < 0) return 0;
        if (p > len) return len;
        return p;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (composingLength > 0) view.deleteFromIme(composingLength);
        view.commitTextFromIme(text);
        composingLength = 0;
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        if (composingLength > 0) view.deleteFromIme(composingLength);
        CharSequence value = text != null ? text : "";
        view.commitTextFromIme(value);
        composingLength = value.length();
        return true;
    }

    @Override
    public boolean finishComposingText() {
        composingLength = 0;
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        composingLength = 0;
        view.deleteFromIme(beforeLength);
        return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_DEL) {
                view.deleteFromIme(1);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                view.performImeEnter();
                return true;
            }
            int u = event.getUnicodeChar();
            if (u != 0) {
                view.commitTextFromIme(new String(Character.toChars(u)));
                return true;
            }
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        view.performImeEnter();
        return true;
    }
}
