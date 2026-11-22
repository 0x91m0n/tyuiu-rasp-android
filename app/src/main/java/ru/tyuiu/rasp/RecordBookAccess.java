package ru.tyuiu.rasp;

import android.app.KeyguardManager;
import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Local data for the optional, device-unlocked grade book. */
final class RecordBookAccess {
    private static final String KEY = "tiu.record-book.aes.v1";
    private static final Object LOCK = new Object();
    private static long generation;

    record Snapshot(long updatedAt, List<TiuRecordBook.Book> books) {
        boolean fresh(long now) {
            return updatedAt <= now && now - updatedAt < TimeUnit.MINUTES.toMillis(10);
        }
    }

    private final KeyguardManager keyguard;
    private final SealedFile credentials;
    private final SealedFile cache;

    RecordBookAccess(Context context) {
        keyguard = context.getSystemService(KeyguardManager.class);
        credentials = new SealedFile(context, "record-book.signin", KEY, false, 8192);
        cache = new SealedFile(context, "record-book.cache", KEY, false, 4 * 1024 * 1024);
    }

    boolean enabled() { synchronized (LOCK) { return credentials.exists(); } }

    long generation() { synchronized (LOCK) { return generation; } }

    void enable(CredentialVault.Entry entry) throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            assertUnlocked();
            disable();
            byte[] clear = CredentialVault.encode(entry.login(), entry.password());
            try {
                credentials.write(credentials.writeRequest(), clear);
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        }
    }

    void disable() throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            generation++;
            if (!credentials.exists() && !cache.exists()) return;
            credentials.deleteKey();
            credentials.deleteFile();
            cache.deleteFile();
        }
    }

    CredentialVault.Entry entry(long ticket) throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            check(ticket);
            var read = credentials.readRequest();
            return CredentialVault.decode(read.decrypt(read.cipher()));
        }
    }

    Snapshot last(long ticket) throws GeneralSecurityException, IOException, JSONException {
        synchronized (LOCK) {
            check(ticket);
            if (!cache.exists()) return null;
            var read = cache.readRequest();
            var clear = read.decrypt(read.cipher());
            try {
                var data = new JSONObject(new String(clear, StandardCharsets.UTF_8));
                return new Snapshot(data.getLong("updatedAt"), TiuRecordBook.parseRecords(data.get("records")));
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        }
    }

    void remember(long ticket, Snapshot snapshot) throws GeneralSecurityException, IOException, JSONException {
        synchronized (LOCK) {
            check(ticket);
            var clear = new JSONObject().put("updatedAt", snapshot.updatedAt())
                    .put("records", TiuRecordBook.recordsJson(snapshot.books()))
                    .toString().getBytes(StandardCharsets.UTF_8);
            try {
                cache.write(cache.writeRequest(), clear);
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        }
    }

    private void check(long ticket) throws GeneralSecurityException {
        assertUnlocked();
        if (ticket != generation || !credentials.exists() || Thread.currentThread().isInterrupted()) {
            throw new GeneralSecurityException("Grade book access changed");
        }
    }

    private void assertUnlocked() throws GeneralSecurityException {
        if (!keyguard.isDeviceSecure() || keyguard.isDeviceLocked()) {
            throw new GeneralSecurityException("Grade book requires an unlocked device with a screen lock");
        }
    }
}
