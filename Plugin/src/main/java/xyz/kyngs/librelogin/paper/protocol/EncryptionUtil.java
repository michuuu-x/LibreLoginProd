/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper.protocol;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption and decryption minecraft util for connection between servers and paid Minecraft
 * account clients.
 *
 * @author Games647 and FastLogin contributors
 */
public final class EncryptionUtil {

    public static final int VERIFY_TOKEN_LENGTH = 4;
    public static final String KEY_PAIR_ALGORITHM = "RSA";

    private static final int RSA_LENGTH = 1_024;

    private EncryptionUtil() {
        throw new RuntimeException("No instantiation of utility classes allowed");
    }

    /**
     * Generate an RSA key pair
     *
     * @return The RSA key pair.
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_PAIR_ALGORITHM);

            keyPairGenerator.initialize(RSA_LENGTH);
            return keyPairGenerator.generateKeyPair();
        } catch (NoSuchAlgorithmException nosuchalgorithmexception) {
            // Should be existing in every vm
            throw new ExceptionInInitializerError(nosuchalgorithmexception);
        }
    }

    /**
     * Generate a random token. This is used to verify that we are communicating with the same
     * player in a login session.
     *
     * @param random random generator
     * @return a token with 4 bytes long
     */
    public static byte[] generateVerifyToken(Random random) {
        byte[] token = new byte[VERIFY_TOKEN_LENGTH];
        random.nextBytes(token);
        return token;
    }

    /**
     * Generate the server id based on client and server data.
     *
     * @param serverId session for the current login attempt
     * @param sharedSecret shared secret between the client and the server
     * @param publicKey public key of the server
     * @return the server id formatted as a hexadecimal string.
     */
    public static String getServerIdHashString(
            String serverId, SecretKey sharedSecret, PublicKey publicKey) {
        byte[] serverHash = getServerIdHash(serverId, publicKey, sharedSecret);
        return (new BigInteger(serverHash)).toString(16);
    }

    /**
     * Decrypts the content and extracts the key spec.
     *
     * @param privateKey private server key
     * @param sharedKey the encrypted shared key
     * @return shared secret key
     */
    public static SecretKey decryptSharedKey(PrivateKey privateKey, byte[] sharedKey)
            throws NoSuchPaddingException,
                    IllegalBlockSizeException,
                    NoSuchAlgorithmException,
                    BadPaddingException,
                    InvalidKeyException {
        return new SecretKeySpec(decrypt(privateKey, sharedKey), "AES");
    }

    public static boolean verifyNonce(
            byte[] expected, PrivateKey decryptionKey, byte[] encryptedNonce)
            throws NoSuchPaddingException,
                    IllegalBlockSizeException,
                    NoSuchAlgorithmException,
                    BadPaddingException,
                    InvalidKeyException {
        byte[] decryptedNonce = decrypt(decryptionKey, encryptedNonce);
        return Arrays.equals(expected, decryptedNonce);
    }

    private static byte[] decrypt(PrivateKey key, byte[] data)
            throws NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    InvalidKeyException,
                    IllegalBlockSizeException,
                    BadPaddingException {
        Cipher cipher = Cipher.getInstance(key.getAlgorithm());
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    private static byte[] getServerIdHash(
            String sessionId, PublicKey publicKey, SecretKey sharedSecret) {
        @SuppressWarnings("deprecation")
        Hasher hasher = Hashing.sha1().newHasher();

        hasher.putBytes(sessionId.getBytes(StandardCharsets.ISO_8859_1));
        hasher.putBytes(sharedSecret.getEncoded());
        hasher.putBytes(publicKey.getEncoded());

        return hasher.hash().asBytes();
    }
}
