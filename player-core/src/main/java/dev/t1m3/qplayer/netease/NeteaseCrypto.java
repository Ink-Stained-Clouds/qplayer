package dev.t1m3.qplayer.netease;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Netease "weapi" client-side request encryption.
 *
 * <p>The web client encrypts every POST body with a double-AES + RSA scheme:
 * <ol>
 *   <li>Stringify JSON request body.</li>
 *   <li>{@code AES-CBC(presetKey, IV)} the body → base64.</li>
 *   <li>Generate a random 16-char secret. {@code AES-CBC(secret, IV)} the
 *       previous base64 → final {@code params} (base64).</li>
 *   <li>RSA-encrypt the reversed secret with the well-known 1024-bit public
 *       key (no padding) → {@code encSecKey} (hex).</li>
 *   <li>POST {@code params} + {@code encSecKey} as form-urlencoded.</li>
 * </ol>
 *
 * <p>{@code presetKey}, {@code IV}, RSA {@code pubKey} and {@code modulus}
 * are public constants baked into the netease web bundle — they are not
 * secret, they protect against trivial replay only. Algorithm is identical
 * to {@code Binaryify/NeteaseCloudMusicApi}'s {@code crypto.js}.
 */
public final class NeteaseCrypto {

    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String RSA_PUB_KEY = "010001";
    private static final String RSA_MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725"
          + "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312"
          + "ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424"
          + "d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";

    private static final char[] BASE62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private NeteaseCrypto() {}

    /**
     * Encrypt the JSON body into {@code params} + {@code encSecKey} ready
     * to POST as form fields to {@code /weapi/...}.
     */
    public static Map<String, String> weapi(String jsonText) throws Exception {
        String secret = randomString(16);
        String first = aesEncrypt(jsonText, PRESET_KEY);
        String params = aesEncrypt(first, secret);
        String encSecKey = rsaEncrypt(secret);
        Map<String, String> out = new HashMap<>();
        out.put("params", params);
        out.put("encSecKey", encSecKey);
        return out;
    }

    private static String aesEncrypt(String data, String key) throws Exception {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] ivBytes = IV.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(ivBytes));
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * Raw RSA (no padding) over the *reversed* secret, then hex-encoded
     * left-padded to 256 chars. This matches the JS reference
     * implementation; standard RSA libraries can't do this (they all
     * apply PKCS#1 / OAEP padding), so we do modPow by hand with
     * {@link BigInteger}.
     */
    private static String rsaEncrypt(String secret) {
        String reversed = new StringBuilder(secret).reverse().toString();
        BigInteger text = new BigInteger(1, reversed.getBytes(StandardCharsets.UTF_8));
        BigInteger pubKey = new BigInteger(RSA_PUB_KEY, 16);
        BigInteger modulus = new BigInteger(RSA_MODULUS, 16);
        BigInteger result = text.modPow(pubKey, modulus);
        String hex = result.toString(16);
        if (hex.length() >= 256) return hex;
        StringBuilder pad = new StringBuilder(256);
        for (int i = hex.length(); i < 256; i++) pad.append('0');
        pad.append(hex);
        return pad.toString();
    }

    private static String randomString(int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) out[i] = BASE62[RNG.nextInt(BASE62.length)];
        return new String(out);
    }
}
