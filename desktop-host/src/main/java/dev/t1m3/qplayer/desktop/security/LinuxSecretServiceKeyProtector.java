package dev.t1m3.qplayer.desktop.security;

import dev.t1m3.qplayer.store.CredentialKeyProtector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Uses libsecret's command-line client to store the data key in Secret Service. */
final class LinuxSecretServiceKeyProtector implements CredentialKeyProtector {

    /** Startup must never wait for a locked KWallet/Keyring prompt. */
    private static final long STARTUP_TIMEOUT_MILLIS = 1_500;
    /** A user-triggered retry runs off-thread and may wait for password entry. */
    private static final long INTERACTIVE_TIMEOUT_MILLIS = 60_000;
    private final String executable;

    private LinuxSecretServiceKeyProtector(String executable) {
        this.executable = executable;
    }

    static LinuxSecretServiceKeyProtector discover() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                File candidate = new File(directory, "secret-tool");
                if (candidate.isFile() && candidate.canExecute()) {
                    return new LinuxSecretServiceKeyProtector(candidate.getAbsolutePath());
                }
            }
        }
        File standard = new File("/usr/bin/secret-tool");
        return standard.isFile() && standard.canExecute()
                ? new LinuxSecretServiceKeyProtector(standard.getAbsolutePath()) : null;
    }

    @Override
    public String id() {
        return "linux-secret-service";
    }

    @Override
    public byte[] protect(byte[] key) throws IOException {
        return protect(key, STARTUP_TIMEOUT_MILLIS);
    }

    @Override
    public byte[] protectInteractively(byte[] key) throws IOException {
        return protect(key, INTERACTIVE_TIMEOUT_MILLIS);
    }

    private byte[] protect(byte[] key, long timeoutMillis) throws IOException {
        String credentialId = UUID.randomUUID().toString();
        byte[] secret = Base64.getEncoder().encode(key);
        final Result result;
        try {
            result = run(timeoutMillis, secret,
                    "store", "--label=QPlayer credentials",
                    "application", "qplayer", "credential", credentialId);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        if (result.exitCode != 0) {
            throw failure("store", result);
        }
        return credentialId.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] unprotect(byte[] protectedKey) throws IOException {
        String credentialId = new String(protectedKey, StandardCharsets.UTF_8);
        Result result = run(STARTUP_TIMEOUT_MILLIS, null, "lookup", "application", "qplayer",
                "credential", credentialId);
        if (result.exitCode != 0) throw failure("lookup", result);
        try {
            return Base64.getDecoder().decode(
                    new String(result.stdout, StandardCharsets.UTF_8).trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Secret Service returned invalid credential data", e);
        }
    }

    @Override
    public byte[] unprotectInteractively(byte[] protectedKey) throws IOException {
        String credentialId = new String(protectedKey, StandardCharsets.UTF_8);
        Result result = run(INTERACTIVE_TIMEOUT_MILLIS, null, "lookup",
                "application", "qplayer", "credential", credentialId);
        if (result.exitCode != 0) throw failure("lookup", result);
        try {
            return Base64.getDecoder().decode(
                    new String(result.stdout, StandardCharsets.UTF_8).trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Secret Service returned invalid credential data", e);
        }
    }

    private Result run(long timeoutMillis, byte[] stdin, String... arguments) throws IOException {
        String[] command = new String[arguments.length + 1];
        command[0] = executable;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).start();
        try {
            try (OutputStream output = process.getOutputStream()) {
                if (stdin != null) output.write(stdin);
            }
            boolean finished;
            try {
                finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Secret Service operation interrupted", e);
            }
            if (!finished) {
                process.destroyForcibly();
                try {
                    process.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Secret Service operation timed out");
            }
            return new Result(process.exitValue(), readAll(process.getInputStream()),
                    readAll(process.getErrorStream()));
        } finally {
            process.destroy();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static IOException failure(String operation, Result result) {
        String detail = new String(result.stderr, StandardCharsets.UTF_8).trim();
        if (detail.isEmpty()) detail = "exit " + result.exitCode;
        return new IOException("Secret Service " + operation + " failed: " + detail);
    }

    private static final class Result {
        final int exitCode;
        final byte[] stdout;
        final byte[] stderr;

        Result(int exitCode, byte[] stdout, byte[] stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
