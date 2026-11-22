package ru.tyuiu.rasp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import javax.crypto.Cipher;
import java.util.Arrays;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

public final class CredentialVaultActivity extends Activity {
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
            | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    private static final String RECORD_BOOK = "tiu_record_book";
    private CredentialVault vault;
    private ThemePalette palette;
    private EditText login;
    private EditText password;
    private Cipher firstWrite;
    private CancellationSignal authentication;
    private Runnable cancelWork;
    private boolean resumed;
    private boolean secureScreen;
    private Runnable whenResumed;
    private long resumeBefore;
    private boolean recordBook;
    private RecordBookAccess gradeAccess;
    private RecordBookAccess.Snapshot shownGrades;
    private TextView gradeStatus;
    private long gradeRequestId;
    private FutureTask<Void> gradeRequest;

    static Intent recordBookIntent(Context context) {
        return new Intent(context, CredentialVaultActivity.class).putExtra(RECORD_BOOK, true);
    }

    @Override protected void onCreate(Bundle state) {
        palette = ThemePalette.resolve(this, new ScheduleStore(this).theme());
        if (palette.light()) setTheme(R.style.AppThemeLight);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setDecorFitsSystemWindows(false);
        vault = new CredentialVault(this);
        recordBook = getIntent().getBooleanExtra(RECORD_BOOK, false);
        if (recordBook) gradeAccess = new RecordBookAccess(this);
        locked("");
        if (state == null && deviceSecure() && !recordBook) {
            getWindow().getDecorView().post(this::unlock);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (secureScreen != deviceSecure()) locked("");
        Runnable pending = whenResumed;
        whenResumed = null;
        if (pending != null && SystemClock.elapsedRealtime() <= resumeBefore && !deviceLocked()) pending.run();
        else if (recordBook && deviceSecure() && !deviceLocked() && gradeAccess.enabled()) loadGrades(false);
    }

    @Override protected void onPause() {
        resumed = false;
        if (password != null || gradeRequest != null || shownGrades != null) locked("");
        super.onPause();
    }

    @Override protected void onDestroy() {
        var prompt = authentication;
        var cleanup = cancelWork;
        authentication = null;
        cancelWork = null;
        whenResumed = null;
        if (prompt != null) prompt.cancel();
        if (cleanup != null) cleanup.run();
        clearEditor();
        cancelGradeRequest();
        super.onDestroy();
    }

    private boolean deviceSecure() { return getSystemService(KeyguardManager.class).isDeviceSecure(); }
    private boolean deviceLocked() { return getSystemService(KeyguardManager.class).isDeviceLocked(); }

    private void unlock() {
        if (!resumed || authentication != null || !deviceSecure() || isFinishing()) return;
        try {
            if (vault.exists()) {
                var request = vault.readRequest();
                String title = recordBook ? "Включить автозагрузку зачётки" : "Разблокировать хранилище";
                authenticate(title, request.cipher(), authorized -> visible(() -> {
                    try {
                        var entry = vault.read(request, authorized);
                        if (recordBook) {
                            gradeAccess.enable(entry);
                            locked("");
                            loadGrades(false);
                        } else {
                            editor(entry.login(), entry.password(), null);
                        }
                    } catch (Exception unavailable) {
                        locked("Не удалось открыть сохранённую запись", true);
                    }
                }), null);
            } else if (!recordBook) {
                authenticate("Открыть хранилище", vault.writeRequest(),
                        authorized -> visible(() -> editor("", "", authorized)), null);
            } else {
                locked("");
            }
        } catch (Exception unavailable) {
            boolean existing = vault.exists();
            locked(existing ? "Не удалось открыть сохранённую запись" : "Не удалось подготовить хранилище", existing);
        }
    }

    private void visible(Runnable work) {
        if (isFinishing() || isDestroyed()) return;
        if (resumed && !deviceLocked()) work.run();
        else {
            whenResumed = work;
            resumeBefore = SystemClock.elapsedRealtime() + 5_000;
        }
    }

    private void authenticate(String title, Cipher cipher, Consumer<Cipher> approved, Runnable cleanup) {
        if (authentication != null || !resumed || !deviceSecure()) {
            if (cleanup != null) cleanup.run();
            return;
        }
        var signal = new CancellationSignal();
        authentication = signal;
        cancelWork = cleanup;
        var callback = new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                if (authentication != signal) return;
                authentication = null;
                cancelWork = null;
                var crypto = result.getCryptoObject();
                try {
                    if (!isFinishing() && !isDestroyed()) {
                        if (cipher == null || (crypto != null && crypto.getCipher() != null)) {
                            approved.accept(crypto == null ? null : crypto.getCipher());
                        } else {
                            locked("Android не подтвердил ключ хранилища");
                        }
                    }
                } finally {
                    if (cleanup != null) cleanup.run();
                }
            }

            @Override public void onAuthenticationError(int code, CharSequence message) {
                if (authentication != signal) return;
                authentication = null;
                cancelWork = null;
                if (cleanup != null) cleanup.run();
                if (code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        || code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) return;
                Toast.makeText(CredentialVaultActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
        try {
            var prompt = new BiometricPrompt.Builder(this).setTitle(title)
                    .setAllowedAuthenticators(AUTHENTICATORS).build();
            if (cipher == null) prompt.authenticate(signal, getMainExecutor(), callback);
            else prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), signal, getMainExecutor(), callback);
        } catch (RuntimeException unavailable) {
            authentication = null;
            cancelWork = null;
            if (cleanup != null) cleanup.run();
            locked("Подтверждение Android недоступно");
        }
    }

    private void save() {
        if (password == null || authentication != null) return;
        if (login.getText().toString().isBlank()) {
            login.setError("Введи логин");
            return;
        }
        if (password.length() == 0) {
            password.setError("Введи пароль");
            return;
        }
        var clear = CredentialVault.encode(login.getText(), password.getText());
        if (firstWrite != null) {
            try {
                Cipher authorized = firstWrite;
                firstWrite = null;
                vault.write(authorized, clear);
                Arrays.fill(clear, (byte) 0);
                locked("Запись сохранена");
                return;
            } catch (java.security.GeneralSecurityException expired) {
                // A long editing session can outlive its pending Keystore operation.
            } catch (Exception failure) {
                Arrays.fill(clear, (byte) 0);
                locked("Не удалось сохранить запись");
                return;
            }
        }
        try {
            authenticate("Подтвердить сохранение", vault.writeRequest(), authorized -> {
                try {
                    vault.write(authorized, clear);
                    locked("Запись сохранена");
                } catch (Exception failure) {
                    locked("Не удалось сохранить запись");
                }
            }, () -> Arrays.fill(clear, (byte) 0));
        } catch (Exception failure) {
            Arrays.fill(clear, (byte) 0);
            locked("Не удалось сохранить запись");
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("Удалить запись?")
                .setMessage("Логин и пароль будут удалены с этого устройства.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> authenticate("Подтвердить удаление", null, unused -> {
                    try {
                        vault.delete();
                        locked("Запись удалена");
                    } catch (Exception failure) {
                        locked("Не удалось удалить запись");
                    }
                }, null)).show();
    }

    private void clearEditor() {
        if (login != null) login.getText().clear();
        if (password != null) password.getText().clear();
        login = null;
        password = null;
        firstWrite = null;
    }

    private void locked(String status) {
        locked(status, false);
    }

    private void locked(String status, boolean recovery) {
        var focus = getCurrentFocus();
        if (password != null && focus != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        clearEditor();
        cancelGradeRequest();
        shownGrades = null;
        var panel = page();
        secureScreen = deviceSecure();
        if (recordBook) {
            recordBookEntry(panel, status);
            return;
        }
        String message = status;
        if (message.isBlank()) {
            message = secureScreen ? "Защищено отпечатком или кодом блокировки телефона."
                    : "Настрой блокировку экрана Android, чтобы пользоваться хранилищем.";
        }
        add(panel, text(message, 15, palette.muted), 0);
        if (secureScreen) {
            add(panel, action(vault.exists() ? "Разблокировать" : "Создать запись", true, this::unlock), 13);
            if (recovery) add(panel, action("Удалить запись", false, this::confirmDelete), 8);
        } else {
            add(panel, action("Настройки блокировки", true,
                    () -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS))), 13);
        }
    }

    private void recordBookEntry(LinearLayout panel, String status) {
        boolean enabled = gradeAccess.enabled();
        if (status.isBlank()) {
            if (!secureScreen) status = "Для зачётки нужна блокировка экрана Android.";
            else if (enabled) status = "Загружаем зачётку…";
            else if (vault.exists()) status = "Подтверди один раз, чтобы приложение могло автоматически входить в зачётку на разблокированном телефоне.";
            else status = "Сохрани логин и пароль ТИУ, чтобы открыть зачётку.";
        }
        gradeStatus = text(status, 15, palette.muted);
        gradeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(panel, gradeStatus, 0);
        if (!secureScreen) {
            add(panel, action("Настройки блокировки", true,
                    () -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS))), 12);
        } else if (!enabled) {
            if (vault.exists()) add(panel, action("Включить автозагрузку", true, this::unlock), 12);
            else add(panel, action("Сохранить логин и пароль", true, this::openVault), 12);
        }
    }

    private void openVault() {
        finish();
        startActivity(new Intent(this, CredentialVaultActivity.class));
    }

    private void recordBookOptions(View anchor) {
        var menu = new android.widget.PopupMenu(this, anchor);
        if (gradeAccess.enabled() && secureScreen) {
            menu.getMenu().add("Обновить баллы").setOnMenuItemClickListener(item -> {
                loadGrades(true);
                return true;
            });
            menu.getMenu().add("Выключить автозагрузку").setOnMenuItemClickListener(item -> {
                cancelGradeRequest();
                try {
                    gradeAccess.disable();
                    locked("");
                } catch (GeneralSecurityException | IOException failure) {
                    locked("Не удалось выключить автозагрузку.");
                }
                return true;
            });
        }
        if (vault.exists()) menu.getMenu().add("Логин и пароль").setOnMenuItemClickListener(item -> {
            openVault();
            return true;
        });
        menu.getMenu().add("Открыть в браузере").setOnMenuItemClickListener(item -> {
            TiuServices.openBrowser(this, TiuRecordBook.URL);
            return true;
        });
        menu.show();
    }

    private void loadGrades(boolean force) {
        if (gradeRequest != null || !resumed || deviceLocked() || !gradeAccess.enabled()) return;
        long requestId = ++gradeRequestId;
        long ticket = gradeAccess.generation();
        var request = new FutureTask<Void>(() -> {
            RecordBookAccess.Snapshot cached = null;
            try {
                cached = gradeAccess.last(ticket);
            } catch (GeneralSecurityException | IOException | org.json.JSONException unreadable) {
                // A missing or unreadable snapshot can be replaced after the server replies.
            }
            if (cached != null) {
                boolean fresh = !force && cached.fresh(System.currentTimeMillis());
                gradeResult(requestId, ticket, cached, "", fresh);
                if (fresh) return null;
            }
            try {
                var books = TiuRecordBook.load(gradeAccess.entry(ticket));
                var snapshot = new RecordBookAccess.Snapshot(System.currentTimeMillis(), books);
                try {
                    gradeAccess.remember(ticket, snapshot);
                } catch (GeneralSecurityException | IOException | org.json.JSONException unavailable) {
                    // The live result still applies while this screen and the sign-in are active.
                }
                gradeResult(requestId, ticket, snapshot, "", true);
            } catch (TiuRecordBook.InteractiveLogin failure) {
                gradeResult(requestId, ticket, null, "ТИУ не принял вход. Проверь логин и пароль.", true);
            } catch (GeneralSecurityException unavailable) {
                gradeResult(requestId, ticket, null, "Доступ к зачётке закрыт. Разблокируй телефон кодом или включи автозагрузку заново.", true);
            } catch (org.json.JSONException changed) {
                gradeResult(requestId, ticket, null, "Не удалось прочитать зачётку ТИУ.", true);
            } catch (IOException unavailable) {
                gradeResult(requestId, ticket, null, "Не удалось обновить баллы. Проверь подключение.", true);
            }
            return null;
        });
        gradeRequest = request;
        new Thread(request, "tiu-record-book").start();
    }

    private void gradeResult(long requestId, long ticket, RecordBookAccess.Snapshot snapshot, String status, boolean done) {
        getMainExecutor().execute(() -> {
            if (requestId != gradeRequestId || gradeRequest == null || !resumed || deviceLocked()
                    || ticket != gradeAccess.generation() || isFinishing() || isDestroyed()) return;
            if (done) gradeRequest = null;
            if (snapshot != null && (shownGrades == null || !shownGrades.books().equals(snapshot.books()))) {
                showGrades(snapshot);
            }
            if (snapshot != null) shownGrades = snapshot;
            if (shownGrades != null) {
                String updated = DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.forLanguageTag("ru"))
                        .format(Instant.ofEpochMilli(shownGrades.updatedAt()).atZone(Schedule.ZONE));
                String updateText = "Обновлено " + updated;
                gradeStatus.setText(status.isBlank() ? updateText : updateText + "\n" + status);
            } else if (!status.isBlank()) {
                gradeStatus.setText(status);
            }
        });
    }

    private void cancelGradeRequest() {
        if (gradeRequest != null) gradeRequest.cancel(true);
        gradeRequest = null;
    }

    private void showGrades(RecordBookAccess.Snapshot snapshot) {
        var panel = page();
        gradeStatus = text("", 13, palette.muted);
        add(panel, gradeStatus, 0);
        boolean hasGrades = false;
        for (var book : snapshot.books()) {
            if (book.semesters().isEmpty()) continue;
            if (snapshot.books().size() > 1 && !book.group().isBlank()) {
                add(panel, gradeHeading(book.group(), 20, palette.text), 18);
            }
            for (var semester : book.semesters()) {
                if (semester.grades().isEmpty()) continue;
                hasGrades = true;
                add(panel, gradeHeading(semester.name(), 16, palette.text), 16);
                for (var grade : semester.grades()) addGrade(panel, grade);
            }
        }
        if (!hasGrades) add(panel, text("В зачётке пока нет записей.", 15, palette.muted), 12);
    }

    private TextView gradeHeading(String label, int size, int color) {
        var heading = text(label, size, color);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setAccessibilityHeading(true);
        return heading;
    }

    private void addGrade(LinearLayout panel, TiuRecordBook.Grade grade) {
        var card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(shape(palette.background, 14));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        var subject = column();
        var title = text(grade.subject().isBlank() ? "Дисциплина" : grade.subject(), 16, palette.text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(subject, title, 0);
        if (!grade.mark().isBlank()) add(subject, text(grade.mark(), 14, palette.accent), 3);
        var details = new StringJoiner(" · ");
        if (!grade.exam().isBlank()) details.add(grade.exam());
        if (!grade.date().isBlank()) details.add(grade.date());
        if (grade.mark().isBlank() && grade.points().isBlank()) details.add("Оценки пока нет");
        if (details.length() > 0) add(subject, text(details.toString(), 13, palette.muted), 3);
        card.addView(subject, new LinearLayout.LayoutParams(0, -2, 1));
        if (!grade.points().isBlank()) {
            var score = column();
            score.setGravity(Gravity.CENTER);
            score.setMinimumWidth(dp(52));
            score.setPadding(dp(7), dp(6), dp(7), dp(6));
            score.setBackground(shape(palette.card, 10));
            var value = text(grade.points(), 20, palette.accent);
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            value.setGravity(Gravity.CENTER);
            add(score, value, 0);
            var label = text("баллы", 11, palette.muted);
            label.setGravity(Gravity.CENTER);
            add(score, label, 0);
            var width = new LinearLayout.LayoutParams(-2, -2);
            width.setMarginStart(dp(10));
            card.addView(score, width);
        }
        add(panel, card, 7);
    }

    private void editor(String user, String secret, Cipher authorized) {
        var panel = page();
        firstWrite = authorized;
        login = field("Логин", user, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = field("Пароль", secret, InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(panel, login, 0);
        add(panel, password, 8);
        var show = new CheckBox(this);
        show.setText("Показать пароль");
        show.setTextColor(palette.text);
        show.setButtonTintList(ColorStateList.valueOf(palette.accent));
        show.setMinHeight(dp(48));
        show.setOnCheckedChangeListener((view, checked) -> {
            if (password == null) return;
            password.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance());
            password.setSelection(password.length());
        });
        add(panel, show, 3);
        if (vault.exists()) {
            add(panel, action("Копировать логин", false, () -> copy("Логин", login)), 3);
            add(panel, action("Копировать пароль", false, () -> copy("Пароль", password)), 7);
        }
        add(panel, action("Сохранить", true, this::save), 11);
        add(panel, action("Заблокировать", false, () -> locked("")), 7);
        if (vault.exists()) add(panel, action("Удалить запись", false, this::confirmDelete), 7);
    }

    private void copy(String label, EditText field) {
        if (field == null || field.length() == 0) return;
        var clip = ClipData.newPlainText(label, field.getText().toString());
        var extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
    }

    private EditText field(String hint, String value, int type) {
        var field = new EditText(this) {
            @Override public boolean onTextContextMenuItem(int id) {
                if (id == android.R.id.copy || id == android.R.id.cut || id == android.R.id.shareText) return true;
                return super.onTextContextMenuItem(id);
            }
        };
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_TEXT | type);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setSaveEnabled(false);
        field.setSaveFromParentEnabled(false);
        field.setHint(hint);
        field.setHintTextColor(palette.muted);
        field.setTextColor(palette.text);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(CredentialVault.MAX_LENGTH)});
        field.setText(value);
        field.setBackground(shape(palette.background, 12));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setMinimumHeight(dp(48));
        return field;
    }

    private LinearLayout page() {
        var shell = new ScrollView(this);
        shell.setBackgroundColor(palette.background);
        shell.setFillViewport(true);
        shell.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        fitInsets(shell);
        var content = column();
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(20), dp(20), dp(20), dp(24));
        shell.addView(content);
        var header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        var back = action("‹", false, this::finish);
        back.setContentDescription("Назад");
        header.addView(back, new LinearLayout.LayoutParams(dp(48), -2));
        var title = text(recordBook ? "Зачётка" : "Логин и пароль", 26, palette.text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setAccessibilityHeading(true);
        DisplayFonts.heading(title, palette);
        var heading = new LinearLayout.LayoutParams(0, -2, 1);
        heading.setMarginStart(dp(12));
        header.addView(title, heading);
        if (recordBook) {
            var options = action("⋮", false, null);
            options.setTextSize(22);
            options.setContentDescription("Действия с зачёткой");
            options.setOnClickListener(this::recordBookOptions);
            header.addView(options, new LinearLayout.LayoutParams(dp(48), -2));
        }
        add(content, header, 0);
        var panel = column();
        panel.setBackground(shape(palette.card, 21));
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        add(content, panel, 14);
        setContentView(shell);
        content.requestFocus();
        var bars = getWindow().getInsetsController();
        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        if (bars != null) bars.setSystemBarsAppearance(palette.light() ? mask : 0, mask);
        return panel;
    }

    private void fitInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var edges = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            return insets;
        });
    }

    private TextView action(String label, boolean primary, Runnable run) {
        var item = new android.widget.Button(this);
        item.setText(label);
        item.setAllCaps(false);
        item.setTextColor(primary ? palette.onAccent : palette.text);
        item.setMinHeight(dp(48));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x307f7f7f),
                shape(primary ? palette.accent : palette.control, 14), null));
        if (run != null) item.setOnClickListener(view -> run.run());
        return item;
    }

    private TextView text(String value, int size, int color) {
        var item = new TextView(this);
        item.setText(value);
        item.setTextSize(size);
        item.setTextColor(color);
        return item;
    }

    private LinearLayout column() {
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View child, int gap) {
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        parent.addView(child, params);
    }

    private GradientDrawable shape(int color, int radius) {
        var drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(radius));
        drawable.setColor(color);
        return drawable;
    }

    private int dp(int count) { return Math.round(count * getResources().getDisplayMetrics().density); }
}
