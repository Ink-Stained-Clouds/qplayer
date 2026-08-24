package dev.t1m3.qplayer.desktop.security;

import dev.t1m3.qplayer.store.CredentialKeyProtection;
import dev.t1m3.qplayer.store.CredentialKeyProtector;
import dev.t1m3.qplayer.util.Logger;

import java.util.Locale;

/** Selects and installs the native credential store for the current desktop OS. */
public final class DesktopCredentialProtection {

    private DesktopCredentialProtection() {
    }

    public static void install() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        CredentialKeyProtector protector;
        if (os.contains("win")) {
            protector = new WindowsDpapiKeyProtector();
        } else if (os.contains("mac")) {
            protector = new MacKeychainKeyProtector();
        } else if (os.contains("linux")) {
            protector = LinuxSecretServiceKeyProtector.discover();
        } else {
            protector = null;
        }

        if (protector == null) {
            Logger.warn("no supported system credential store found; owner-only fallback remains active");
            return;
        }
        CredentialKeyProtection.install(protector);
        Logger.info("credential key protection: {}", protector.id());
    }
}
