package dev.t1m3.qplayer.desktop;

import dev.t1m3.qplayer.util.Logger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;

/**
 * Desktop {@link Logger.Sink} that forwards the shared player-core logger to
 * log4j2 (config in {@code log4j2.xml}: colored console + rolling {@code logs/}
 * file). Installed from {@link Main} at startup so the log4j dependency stays
 * out of player-core and the Android build.
 */
final class Log4j2Sink implements Logger.Sink {

    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger("qplayer");

    @Override
    public void log(String level, String message) {
        LOG.log(level(level), message);
    }

    @Override
    public void exception(String message, Throwable t) {
        LOG.error(message, t);
    }

    private static Level level(String level) {
        return "E".equals(level) ? Level.ERROR
                : "W".equals(level) ? Level.WARN : Level.INFO;
    }
}
