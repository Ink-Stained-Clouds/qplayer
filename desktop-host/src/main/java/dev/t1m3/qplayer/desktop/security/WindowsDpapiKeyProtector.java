package dev.t1m3.qplayer.desktop.security;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import dev.t1m3.qplayer.store.CredentialKeyProtector;

/** Protects the data key with Windows DPAPI, scoped to the current user profile. */
final class WindowsDpapiKeyProtector implements CredentialKeyProtector {

    @Override
    public String id() {
        return "windows-dpapi-user";
    }

    @Override
    public byte[] protect(byte[] key) {
        return Crypt32Util.cryptProtectData(key, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
    }

    @Override
    public byte[] unprotect(byte[] protectedKey) {
        return Crypt32Util.cryptUnprotectData(
                protectedKey, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
    }
}
