package ru.tyuiu.rasp;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialVault {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "tiu.credentials.aes.v1";
    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int HEADER = 1 + IV_LENGTH;
    private static final int MAX_ENVELOPE = 8192;
    static final int MAX_LENGTH = 1024;

    record Read(Cipher cipher, byte[] envelope) {}
    record Entry(String login, String password) {}

    private final AtomicFile file;

    CredentialVault(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "credentials.vault"));
    }

    boolean exists() { return file.getBaseFile().exists(); }

    Read readRequest() throws GeneralSecurityException, IOException {
        byte[] envelope;
        try (var input = file.openRead()) {
            envelope = input.readNBytes(MAX_ENVELOPE + 1);
        }
        if (envelope.length < HEADER + TAG_LENGTH || envelope.length > MAX_ENVELOPE || envelope[0] != VERSION) {
            throw new IOException("Invalid vault envelope");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(TAG_LENGTH * 8, envelope, 1, IV_LENGTH));
        return new Read(cipher, envelope);
    }

    Cipher writeRequest() throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(!exists()));
        return cipher;
    }

    Entry read(Read request, Cipher authorized) throws GeneralSecurityException, IOException {
        byte[] envelope = request.envelope();
        byte[] clear = authorized.doFinal(envelope, HEADER, envelope.length - HEADER);
        try {
            if (clear.length < Integer.BYTES) throw new IOException("Invalid credential entry");
            int loginSize = ByteBuffer.wrap(clear).getInt();
            if (loginSize < 0 || loginSize > clear.length - Integer.BYTES) throw new IOException("Invalid login size");
            int passwordOffset = Integer.BYTES + loginSize;
            return new Entry(new String(clear, Integer.BYTES, loginSize, StandardCharsets.UTF_8),
                    new String(clear, passwordOffset, clear.length - passwordOffset, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    void write(Cipher authorized, byte[] clear) throws GeneralSecurityException, IOException {
        byte[] iv = authorized.getIV();
        if (iv == null || iv.length != IV_LENGTH || HEADER + TAG_LENGTH + clear.length > MAX_ENVELOPE) {
            throw new IOException("Invalid vault encryption parameters");
        }
        byte[] sealed = authorized.doFinal(clear);
        FileOutputStream output = file.startWrite();
        try {
            output.write(VERSION);
            output.write(iv);
            output.write(sealed);
            file.finishWrite(output);
        } catch (IOException failure) {
            file.failWrite(output);
            throw failure;
        }
    }

    static byte[] encode(CharSequence login, CharSequence password) {
        byte[] user = login.toString().getBytes(StandardCharsets.UTF_8);
        byte[] secret = password.toString().getBytes(StandardCharsets.UTF_8);
        try {
            return ByteBuffer.allocate(Integer.BYTES + user.length + secret.length)
                    .putInt(user.length).put(user).put(secret).array();
        } finally {
            Arrays.fill(user, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
    }

    void delete() throws GeneralSecurityException, IOException {
        file.delete();
        if (exists()) throw new IOException("Vault file could not be deleted");
        KeyStore store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        store.deleteEntry(ALIAS);
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        if (!create) {
            KeyStore store = KeyStore.getInstance(PROVIDER);
            store.load(null);
            if (store.getKey(ALIAS, null) instanceof SecretKey secret) return secret;
            throw new GeneralSecurityException("Vault key is missing");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .build());
        return generator.generateKey();
    }
}
