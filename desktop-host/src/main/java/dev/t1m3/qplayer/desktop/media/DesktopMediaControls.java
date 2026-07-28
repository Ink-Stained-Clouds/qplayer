package dev.t1m3.qplayer.desktop.media;

import dev.t1m3.qplayer.bridge.PlayerController;

/** Native now-playing surface exposed by a desktop operating system. */
public interface DesktopMediaControls extends PlayerController.PlaybackListener {
    void start();
    void shutdown();
}
