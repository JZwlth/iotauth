/*
 * Copyright (c) 2016, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * IOTAUTH_COPYRIGHT_VERSION_1
 */

package org.iot.auth;

import org.iot.auth.io.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.util.Enumeration;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A class for performing cryptography operations for Auth.
 * @author Hokeun Kim
 */
public class AuthCrypto {
    private static final Logger logger = LoggerFactory.getLogger(AuthCrypto.class);

    public AuthCrypto(String keyStorePath, String keyStorePassword) throws IOException, KeyStoreException,
            CertificateException, NoSuchAlgorithmException, UnrecoverableEntryException, IllegalArgumentException
    {
        this.authKeyStore = loadKeyStore(keyStorePath, keyStorePassword);

        if (authKeyStore.size() != 1) {
            throw new IllegalArgumentException("Auth key store must contain one key entry.");
        }
        Enumeration<String> aliases = authKeyStore.aliases();

        KeyStore.ProtectionParameter protParam =
                new KeyStore.PasswordProtection(keyStorePassword.toCharArray());
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            KeyStore.PrivateKeyEntry pkEntry = (KeyStore.PrivateKeyEntry) authKeyStore.getEntry(alias, protParam);
            logger.debug("Alias {}: , Cert: {}, Key: {}", alias, pkEntry.getCertificate(), pkEntry.getPrivateKey());
            this.authPrivateKey = pkEntry.getPrivateKey();
        }
        this.authSignAlgorithm = "SHA256withRSA";
        this.authPublicCipherAlgorithm = "RSA/ECB/PKCS1PADDING";
    }

    public boolean verifySignedData(Buffer data, Buffer signature, PublicKey publicKey)
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException
    {
        Signature verifier;
        verifier = Signature.getInstance(authSignAlgorithm);
        verifier.initVerify(publicKey);
        verifier.update(data.getRawBytes());
        return verifier.verify(signature.getRawBytes());
    }

    public Buffer signWithPrivateKey(Buffer input)
            throws IllegalArgumentException {
        try {
            Signature signer = Signature.getInstance(authSignAlgorithm);
            signer.initSign(authPrivateKey); // cf) initVerify
            signer.update(input.getRawBytes());
            return new Buffer(signer.sign());
        }
        catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalArgumentException("Problem signing with private key\n" + e.getMessage());
        }
    }

    public Buffer privateDecrypt(Buffer input)
            throws IllegalArgumentException {
        return performAsymmetricCrypto(Cipher.DECRYPT_MODE, input, authPrivateKey, authPublicCipherAlgorithm);
    }

    public Buffer publicEncrypt(Buffer input, PublicKey publicKey)
            throws IllegalArgumentException {
        return performAsymmetricCrypto(Cipher.ENCRYPT_MODE, input, publicKey, authPublicCipherAlgorithm);
    }

    public static KeyStore loadKeyStore(String filePath, String password) throws IOException, KeyStoreException,
            CertificateException, NoSuchAlgorithmException {
        File keyStoreFile = new File(filePath);

        FileInputStream keyStoreFIS = new FileInputStream(keyStoreFile);

        // check if it's pfx
        KeyStore keyStore = null;

        if (keyStoreFile.getName().endsWith(".pfx")) {
            keyStore = KeyStore.getInstance("pkcs12");
        }
        else {
            keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        }

        keyStore.load(keyStoreFIS, password.toCharArray());
        return keyStore;
    }

    public static X509Certificate loadCertificate(String filePath) {
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            FileInputStream inStream = new FileInputStream(filePath);
            return (X509Certificate) certFactory.generateCertificate(inStream);
        } catch (CertificateException | FileNotFoundException e) {
            throw new IllegalArgumentException("Problem loading public key " + filePath + "\n" + e.getMessage());
        }
    }

    public static PublicKey loadPublicKey(String filePath) {
        return loadCertificate(filePath).getPublicKey();
    }

    public static Buffer getRandomBytes(int size) {
        SecureRandom random = new SecureRandom();
        byte seed[] = random.generateSeed(size);
        byte[] randomBytes = new byte[size];
        random.setSeed(seed);
        random.nextBytes(randomBytes);
        return new Buffer(randomBytes);
    }

    public static Buffer symmetricEncrypt(Buffer input, Buffer key, String cipherAlgorithm) throws RuntimeException {
        Cipher cipher = getCipher(Cipher.ENCRYPT_MODE, cipherAlgorithm, key, null);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            // write initialization vector first
            byte[] initVector = cipher.getIV();
            if (initVector != null) {
                byteArrayOutputStream.write(initVector);
            }
            byteArrayOutputStream.write(cipher.doFinal(input.getRawBytes()));
        } catch (IllegalBlockSizeException | BadPaddingException | IOException e) {
            throw new RuntimeException("Problem processing " + input.toHexString() + "\n" + e.getMessage());
        }

        return new Buffer(byteArrayOutputStream.toByteArray());
    }

    public static Buffer symmetricDecrypt(Buffer cipherText, Buffer key, String cipherAlgorithm) throws RuntimeException {
        Cipher cipher = getCipher(Cipher.DECRYPT_MODE, cipherAlgorithm, key, cipherText);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        int ivSize = 0;
        if (cipher.getIV() != null) {
            ivSize = cipher.getIV().length;
        }
        try {
            byteArrayOutputStream.write(cipher.doFinal(cipherText.getRawBytes(), ivSize, cipherText.length() - ivSize));
        } catch (IllegalBlockSizeException | BadPaddingException | IOException e) {
            throw new IllegalArgumentException("Problem processing " + cipherText.toHexString() + "\n" + e.getMessage());
        }
        return new Buffer(byteArrayOutputStream.toByteArray());
    }

    public static int getHashLength(String hashAlgorithm) throws RuntimeException {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(hashAlgorithm);
            return messageDigest.getDigestLength();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialize messageDigest.\n" + e.getMessage());
        }
    }

    public static Buffer hash(Buffer input, String hashAlgorithm) throws RuntimeException {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(hashAlgorithm);
            return new Buffer(messageDigest.digest(input.getRawBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialize messageDigest.\n" + e.getMessage());
        }
    }

    private PrivateKey loadPrivateKey(String filePath) {
        if (!filePath.endsWith(".der")) {
            throw new IllegalArgumentException("Private key should be in DER format. " + filePath);
        }
        PKCS8EncodedKeySpec keySpec = null;
        try {
            keySpec = new PKCS8EncodedKeySpec(readBinaryFile(filePath));
        } catch (IOException e) {
            e.printStackTrace();
        }
        KeyFactory keyFactory;
        try {
            keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Problem loading private key " + filePath + "\n" + e.getMessage());
        }
    }

    private byte[] readBinaryFile(String filePath) throws IOException {
        File file = new File(filePath);
        DataInputStream dataInStream = new DataInputStream(new FileInputStream(filePath));
        byte[] bytes = new byte[(int) file.length()];
        dataInStream.readFully(bytes);
        return bytes;
    }

    private Buffer performAsymmetricCrypto(int operationMode, Buffer input, Key key, String cipherAlgorithm)
            throws IllegalArgumentException {
        Cipher cipher = null;
        try {
            cipher = Cipher.getInstance(cipherAlgorithm);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new IllegalArgumentException("Problem getting instance " + input + "\n" + e.getMessage());
        }

        try {
            cipher.init(operationMode, key);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Problem with key " + input + "\n" + e.getMessage());
        }

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        try {
            byteArrayOutputStream.write(cipher.doFinal(input.getRawBytes()));
        } catch (IllegalBlockSizeException | BadPaddingException | IOException e) {
            throw new IllegalArgumentException("Problem processing crypto " + input + "\n" + e.getMessage());
        }
        return new Buffer(byteArrayOutputStream.toByteArray());
    }

    private static Cipher getCipher(int operationMode, String cipherAlgo, Buffer cipherKey, Buffer cipherText)
            throws IllegalArgumentException {
        String[] tokens = cipherAlgo.split("-");
        if (tokens.length < 1) {
            throw new IllegalArgumentException("Invalid cipher algorithm: " + cipherAlgo);
        }

        Cipher cipher;
        try {
            cipher = Cipher.getInstance(cipherAlgo);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new IllegalArgumentException("Invalid cipher algorithm: " + cipherAlgo + "\n" + e.getMessage());
        }

        String[] cipherAlgoTokens = cipher.getAlgorithm().split("/");
        SecretKeySpec secretKeySpec = new SecretKeySpec(cipherKey.getRawBytes(), cipherAlgoTokens[0]);
        logger.debug("cipher.getAlgorithm(): {}", cipher.getAlgorithm());

        IvParameterSpec ivSpec = null;
        if (operationMode == Cipher.DECRYPT_MODE) {
            int blockSize = cipher.getBlockSize();
            byte[] initVector = cipherText.slice(0, blockSize).getRawBytes();
            ivSpec = new IvParameterSpec(initVector);
        }

        try {
            if (ivSpec != null) {
                cipher.init(operationMode, secretKeySpec, ivSpec);
            } else {
                cipher.init(operationMode, secretKeySpec);
            }
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new IllegalArgumentException("Invalid cipher algorithm: " + cipherAlgo + "\n" + e.getMessage());
        }

        return cipher;
    }

    private KeyStore authKeyStore;
    private PrivateKey authPrivateKey;
    private String authSignAlgorithm;
    private String authPublicCipherAlgorithm;
}
