package ru.tyuiu.rasp;

import android.content.Context;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;

final class CredentialVault {
    static final int MAX_LENGTH = 1024;
    private final SealedFile file;
    private final RecordBookAccess grades;

    record Entry(String login, String password) {}

    CredentialVault(Context context) {
        file = new SealedFile(context, "credentials.vault", "tiu.credentials.aes.v1", true, 8192);
        grades = new RecordBookAccess(context);
    }

    boolean exists() { return file.exists(); }

    SealedFile.Read readRequest() throws GeneralSecurityException, IOException { return file.readRequest(); }

    Cipher writeRequest() throws GeneralSecurityException, IOException { return file.writeRequest(); }

    void write(Cipher authorized, byte[] clear) throws GeneralSecurityException, IOException {
        grades.disable();
        file.write(authorized, clear);
    }

    Entry read(SealedFile.Read request, Cipher authorized) throws GeneralSecurityException, IOException {
        return decode(request.decrypt(authorized));
    }

    static Entry decode(byte[] clear) throws IOException {
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
        grades.disable();
        file.deleteFile();
        file.deleteKey();
    }
}
