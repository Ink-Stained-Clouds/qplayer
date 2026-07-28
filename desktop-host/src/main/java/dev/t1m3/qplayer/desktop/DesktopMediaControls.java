package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.bridge.PlayerController;

/** Native now-playing surface exposed by a desktop operating system. */
interface DesktopMediaControls extends PlayerController.PlaybackListener {
    void start();
    void shutdown();
}
