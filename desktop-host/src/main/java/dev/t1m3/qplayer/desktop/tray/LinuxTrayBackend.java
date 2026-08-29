package dev.t1m3.qplayer.desktop.tray;

/** Common surface for the modern SNI tray and the AppIndicator fallback. */
abstract class LinuxTrayBackend {
    abstract Object addItem(String label, Runnable action);
    abstract void addSeparator();
    abstract void setLabel(Object handle, String label);
    abstract void setIconPng(byte[] png);
    abstract void setTooltip(String tip);
    void setLeftClickAction(Runnable action) { }
    abstract boolean install();
    abstract void shutdown();
}
