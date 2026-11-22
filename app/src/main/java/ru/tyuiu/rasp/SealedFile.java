package ru.tyuiu.rasp;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SealedFile {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int HEADER = 1 + IV_LENGTH;

    record Read(Cipher cipher, byte[] envelope) {
        byte[] decrypt(Cipher authorized) throws GeneralSecurityException {
            return authorized.doFinal(envelope, HEADER, envelope.length - HEADER);
        }
    }

    private final AtomicFile file;
    private final String alias;
    private final boolean confirmEachUse;
    private final int maxEnvelope;

    SealedFile(Context context, String name, String alias, boolean confirmEachUse, int maxEnvelope) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), name));
        this.alias = alias;
        this.confirmEachUse = confirmEachUse;
        this.maxEnvelope = maxEnvelope;
    }

    boolean exists() { return file.getBaseFile().exists(); }

    Read readRequest() throws GeneralSecurityException, IOException {
        byte[] envelope;
        try (var input = file.openRead()) {
            envelope = input.readNBytes(maxEnvelope + 1);
        }
        if (envelope.length < HEADER + TAG_LENGTH || envelope.length > maxEnvelope || envelope[0] != VERSION) {
            throw new IOException("Invalid encrypted file");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(TAG_LENGTH * 8, envelope, 1, IV_LENGTH));
        return new Read(cipher, envelope);
    }

    Cipher writeRequest() throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        return cipher;
    }

    void write(Cipher authorized, byte[] clear) throws GeneralSecurityException, IOException {
        byte[] iv = authorized.getIV();
        if (iv == null || iv.length != IV_LENGTH || HEADER + TAG_LENGTH + clear.length > maxEnvelope) {
            throw new IOException("Invalid encryption parameters");
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

    void deleteFile() throws IOException {
        file.delete();
        if (exists()) throw new IOException("Encrypted file could not be deleted");
    }

    void deleteKey() throws GeneralSecurityException, IOException {
        var store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        store.deleteEntry(alias);
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        var store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        if (store.getKey(alias, null) instanceof SecretKey secret) return secret;
        if (!create || exists()) throw new GeneralSecurityException("Encryption key is missing");
        var builder = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE);
        if (confirmEachUse) {
            builder.setUserAuthenticationRequired(true).setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        } else {
            builder.setUnlockedDeviceRequired(true);
        }
        var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(builder.build());
        return generator.generateKey();
    }
}
