package de.klackwerk.svenager

import grails.core.GrailsApplication
import grails.util.Environment

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * AES-256-GCM encryption for secrets at rest (repository deploy keys,
 * secret configuration variables).
 *
 * The key comes from `svenager.security.encryptionKey` (env
 * SVENAGER_ENCRYPTION_KEY, base64-encoded 32 bytes). In development and
 * test, a key is generated once and kept under build/ so restarts keep
 * working; in production a missing key is a hard error.
 */
class CryptoService {

    static final String CIPHER = 'AES/GCM/NoPadding'
    static final int IV_LENGTH = 12
    static final int TAG_BITS = 128

    GrailsApplication grailsApplication

    private final SecureRandom random = new SecureRandom()
    private volatile SecretKey cachedKey

    String encrypt(String plaintext) {
        byte[] iv = new byte[IV_LENGTH]
        random.nextBytes(iv)
        Cipher cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv))
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes('UTF-8'))
        byte[] combined = new byte[iv.length + ciphertext.length]
        System.arraycopy(iv, 0, combined, 0, iv.length)
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length)
        Base64.encoder.encodeToString(combined)
    }

    String decrypt(String encoded) {
        byte[] combined = Base64.decoder.decode(encoded)
        byte[] iv = combined[0..<IV_LENGTH] as byte[]
        byte[] ciphertext = combined[IV_LENGTH..<combined.length] as byte[]
        Cipher cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv))
        new String(cipher.doFinal(ciphertext), 'UTF-8')
    }

    /** Loads the key now so a missing production key fails at startup. */
    void verifyKey() {
        getKey()
    }

    private SecretKey getKey() {
        if (cachedKey == null) {
            synchronized (this) {
                if (cachedKey == null) {
                    cachedKey = loadKey()
                }
            }
        }
        cachedKey
    }

    private SecretKey loadKey() {
        String configured = grailsApplication?.config?.getProperty('svenager.security.encryptionKey')
        if (configured) {
            byte[] raw = Base64.decoder.decode(configured)
            if (raw.length != 32) {
                throw new IllegalStateException('svenager.security.encryptionKey must be 32 base64-encoded bytes')
            }
            return new SecretKeySpec(raw, 'AES')
        }
        if (Environment.current in [Environment.DEVELOPMENT, Environment.TEST]) {
            return devKey()
        }
        throw new IllegalStateException(
                'No encryption key configured. Set SVENAGER_ENCRYPTION_KEY to 32 random base64-encoded bytes ' +
                        '(e.g. `openssl rand -base64 32`).')
    }

    /** Development/test only: generate once and persist under build/. */
    private SecretKey devKey() {
        File file = new File('build/dev-encryption-key')
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            KeyGenerator generator = KeyGenerator.getInstance('AES')
            generator.init(256)
            file.text = Base64.encoder.encodeToString(generator.generateKey().encoded)
        }
        new SecretKeySpec(Base64.decoder.decode(file.text.trim()), 'AES')
    }
}
