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
import java.util.function.Consumer;

public final class CredentialVaultActivity extends Activity {
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
            | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
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

    @Override protected void onCreate(Bundle state) {
        palette = ThemePalette.resolve(this, new ScheduleStore(this).theme());
        if (palette.light()) setTheme(R.style.AppThemeLight);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setDecorFitsSystemWindows(false);
        vault = new CredentialVault(this);
        locked("");
        if (state == null && deviceSecure()) getWindow().getDecorView().post(this::unlock);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (secureScreen != deviceSecure()) locked("");
        Runnable pending = whenResumed;
        whenResumed = null;
        if (pending != null && SystemClock.elapsedRealtime() <= resumeBefore && !deviceLocked()) pending.run();
    }

    @Override protected void onPause() {
        resumed = false;
        if (password != null) locked("");
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
        super.onDestroy();
    }

    private boolean deviceSecure() { return getSystemService(KeyguardManager.class).isDeviceSecure(); }
    private boolean deviceLocked() { return getSystemService(KeyguardManager.class).isDeviceLocked(); }

    private void unlock() {
        if (!resumed || authentication != null || !deviceSecure() || isFinishing()) return;
        try {
            if (vault.exists()) {
                var request = vault.readRequest();
                authenticate("Разблокировать хранилище", request.cipher(), authorized -> visible(() -> {
                    try {
                        var entry = vault.read(request, authorized);
                        editor(entry.login(), entry.password(), null);
                    } catch (Exception unavailable) {
                        locked("Не удалось открыть сохранённую запись", true);
                    }
                }), null);
            } else {
                authenticate("Открыть хранилище", vault.writeRequest(),
                        authorized -> visible(() -> editor("", "", authorized)), null);
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
        var panel = page();
        secureScreen = deviceSecure();
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
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            var edges = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            return insets;
        });
        var content = column();
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(20), dp(20), dp(20), dp(24));
        shell.addView(content);
        var header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        var back = action("‹", false, this::finish);
        back.setContentDescription("Назад");
        header.addView(back, new LinearLayout.LayoutParams(dp(48), -2));
        var title = text("Логин и пароль", 26, palette.text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setAccessibilityHeading(true);
        DisplayFonts.heading(title, palette);
        var heading = new LinearLayout.LayoutParams(0, -2, 1);
        heading.setMarginStart(dp(12));
        header.addView(title, heading);
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

    private TextView action(String label, boolean primary, Runnable run) {
        var item = new android.widget.Button(this);
        item.setText(label);
        item.setAllCaps(false);
        item.setTextColor(primary ? palette.onAccent : palette.text);
        item.setMinHeight(dp(48));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x307f7f7f),
                shape(primary ? palette.accent : palette.control, 14), null));
        item.setOnClickListener(view -> run.run());
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
