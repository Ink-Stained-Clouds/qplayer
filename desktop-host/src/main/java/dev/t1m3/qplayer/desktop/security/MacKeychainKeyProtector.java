package dev.t1m3.qplayer.desktop.security;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import dev.t1m3.qplayer.store.CredentialKeyProtector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stores the random data key as a generic password in the user's macOS Keychain. */
final class MacKeychainKeyProtector implements CredentialKeyProtector {

    private static final byte[] SERVICE =
            "dev.t1m3.qplayer.credentials".getBytes(StandardCharsets.UTF_8);

    private interface Security extends Library {
        Security INSTANCE = Native.load("Security", Security.class);

        int SecKeychainAddGenericPassword(Pointer keychain, int serviceLength,
                byte[] service, int accountLength, byte[] account, int passwordLength,
                byte[] password, PointerByReference itemRef);

        int SecKeychainFindGenericPassword(Pointer keychainOrArray, int serviceLength,
                byte[] service, int accountLength, byte[] account,
                IntByReference passwordLength, PointerByReference passwordData,
                PointerByReference itemRef);

        int SecKeychainItemFreeContent(Pointer attributes, Pointer data);
    }

    @Override
    public String id() {
        return "macos-keychain";
    }

    @Override
    public byte[] protect(byte[] key) throws IOException {
        byte[] account = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        PointerByReference item = new PointerByReference();
        int status = Security.INSTANCE.SecKeychainAddGenericPassword(null,
                SERVICE.length, SERVICE, account.length, account,
                key.length, key, item);
        releaseItem(item.getValue());
        if (status != 0) throw status("store", status);
        return account;
    }

    @Override
    public byte[] unprotect(byte[] protectedKey) throws IOException {
        IntByReference length = new IntByReference();
        PointerByReference data = new PointerByReference();
        PointerByReference item = new PointerByReference();
        int status = Security.INSTANCE.SecKeychainFindGenericPassword(null,
                SERVICE.length, SERVICE, protectedKey.length, protectedKey,
                length, data, item);
        if (status != 0) {
            releaseItem(item.getValue());
            throw status("read", status);
        }
        Pointer value = data.getValue();
        try {
            return value.getByteArray(0, length.getValue());
        } finally {
            Security.INSTANCE.SecKeychainItemFreeContent(null, value);
            releaseItem(item.getValue());
        }
    }

    private static void releaseItem(Pointer item) {
        if (item != null) new CoreFoundation.CFTypeRef(item).release();
    }

    private static IOException status(String operation, int status) {
        return new IOException("macOS Keychain " + operation + " failed (OSStatus "
                + status + ")");
    }
}
