package ptshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption at rest for saved captures and exported archives.
 *
 * <pre>
 * file   = "BSV1" | version(1)=1 | kdf(1)=1 | iterations(4, big endian) | salt(16) | iv(12) | ciphertext+tag(16)
 * key    = PBKDF2-HMAC-SHA256(password, salt, iterations, 256 bit)
 * cipher = AES-256-GCM, fresh random 96-bit IV for every encryption, the whole header (up to and including the IV) is the AAD
 * </pre>
 * The password is never stored: it is typed once per session and only held in memory (and wiped on {@link #lock()}). Because the header
 * is authenticated and the iteration count is range-checked, a tampered or hostile file cannot make us derive a key with an absurd cost,
 * and any modification is detected (GCM tag).
 */
final class Vault {
    static final int ITER_DEFAULT = 600_000, ITER_MIN = 100_000, ITER_MAX = 2_000_000;
    static final int MIN_PASSWORD = 8;
    /** Rounds used for NEW keys; only the self-tests lower it (never below ITER_MIN) to run many scenarios quickly. */
    static int defaultIterations = ITER_DEFAULT;
    private static final byte[] MAGIC = {'B', 'S', 'V', '1'};
    private static final int SALT_LEN = 16, IV_LEN = 12, HEADER_LEN = 4 + 1 + 1 + 4 + SALT_LEN + IV_LEN, TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    /** Wrong password, or the data was modified / is corrupt (GCM cannot tell these apart, deliberately). */
    static final class BadPasswordException extends IOException {
        BadPasswordException() {
            super("Wrong password, or the encrypted data is damaged.");
        }
    }

    private char[] password;
    private byte[] salt;
    private int iterations = ITER_DEFAULT;
    private final Map<String, byte[]> keys = new HashMap<>();

    static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < HEADER_LEN + 16) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }

    synchronized boolean isUnlocked() {
        return password != null;
    }

    /** Starts a new vault with this password (fresh random salt). Slow (key derivation): call off the UI thread. */
    synchronized void setNew(char[] pw) throws GeneralSecurityException {
        lock();
        password = pw.clone();
        salt = new byte[SALT_LEN];
        RNG.nextBytes(salt);
        iterations = Math.max(ITER_MIN, Math.min(ITER_MAX, defaultIterations));
        key(salt, iterations);
    }

    /**
     * Unlocks with a password for data that was encrypted earlier: salt and iteration count are taken from {@code sample} (any encrypted
     * blob), so everything written from now on reuses them. Verifies the password by decrypting the sample.
     */
    synchronized void unlock(char[] pw, byte[] sample) throws IOException, GeneralSecurityException {
        if (!isEncrypted(sample)) throw new IOException("Not an encrypted BurpShot file.");
        Header h = header(sample);
        char[] oldPw = password;
        byte[] oldSalt = salt;
        int oldIter = iterations;
        Map<String, byte[]> oldKeys = new HashMap<>(keys);
        keys.clear(); // keys derived from a different password must never be reused for this attempt
        password = pw.clone();
        salt = h.salt;
        iterations = h.iter;
        try {
            decrypt(sample);
        } catch (IOException | GeneralSecurityException e) {
            for (byte[] k : keys.values()) Arrays.fill(k, (byte) 0); // the keys of the failed attempt
            keys.clear();
            keys.putAll(oldKeys);
            Arrays.fill(password, '\0');
            password = oldPw;
            salt = oldSalt;
            iterations = oldIter;
            throw e;
        }
        if (oldPw != null) Arrays.fill(oldPw, '\0');
        for (byte[] k : oldKeys.values()) Arrays.fill(k, (byte) 0);
    }

    /** Forgets the password and every derived key. */
    synchronized void lock() {
        if (password != null) Arrays.fill(password, '\0');
        password = null;
        for (byte[] k : keys.values()) Arrays.fill(k, (byte) 0);
        keys.clear();
        salt = null;
    }

    private static final class Header {
        int iter;
        byte[] salt;
        byte[] iv;
    }

    private static Header header(byte[] blob) throws IOException {
        if (!isEncrypted(blob)) throw new IOException("Not an encrypted BurpShot file.");
        if (blob[4] != 1 || blob[5] != 1) throw new IOException("Unsupported encrypted file version.");
        ByteBuffer b = ByteBuffer.wrap(blob, 6, 4 + SALT_LEN + IV_LEN);
        Header h = new Header();
        h.iter = b.getInt();
        if (h.iter < ITER_MIN || h.iter > ITER_MAX) throw new IOException("Encrypted file uses unsafe key-derivation settings.");
        h.salt = new byte[SALT_LEN];
        b.get(h.salt);
        h.iv = new byte[IV_LEN];
        b.get(h.iv);
        return h;
    }

    private byte[] key(byte[] s, int iter) throws GeneralSecurityException {
        String id = java.util.Base64.getEncoder().encodeToString(s) + ":" + iter;
        byte[] k = keys.get(id);
        if (k == null) {
            PBEKeySpec spec = new PBEKeySpec(password, s, iter, 256);
            try {
                k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
            keys.put(id, k);
        }
        return k;
    }

    synchronized byte[] encrypt(byte[] plain) throws IOException {
        if (password == null || salt == null) throw new IOException("The vault is locked.");
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            byte[] head = ByteBuffer.allocate(HEADER_LEN).put(MAGIC).put((byte) 1).put((byte) 1).putInt(iterations).put(salt).put(iv).array();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(salt, iterations), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(head);
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[head.length + ct.length];
            System.arraycopy(head, 0, out, 0, head.length);
            System.arraycopy(ct, 0, out, head.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed: " + e.getClass().getSimpleName(), e);
        }
    }

    synchronized byte[] decrypt(byte[] blob) throws IOException, GeneralSecurityException {
        if (password == null) throw new IOException("The vault is locked.");
        Header h = header(blob);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(h.salt, h.iter), "AES"), new GCMParameterSpec(TAG_BITS, h.iv));
        c.updateAAD(blob, 0, HEADER_LEN);
        try {
            return c.doFinal(blob, HEADER_LEN, blob.length - HEADER_LEN);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new BadPasswordException();
        }
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
