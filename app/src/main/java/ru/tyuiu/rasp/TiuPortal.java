package ru.tyuiu.rasp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.net.http.SslError;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.function.Consumer;

@SuppressLint("ViewConstructor") // The portal requires a destination and a status callback.
final class TiuPortal extends WebView {
    private static final String SSO_HOST = "sso.tyuiu.ru";
    private static final String MY_HOST = "my.tyuiu.ru";
    private static final String EDUCON_HOST = "educon2.tyuiu.ru";
    private static final String KEYCLOAK = "/realms/master/";
    private static final String SSO_LOGIN = KEYCLOAK + "protocol/openid-connect/auth";
    private static final String SSO_ACTION = KEYCLOAK + "login-actions/authenticate";

    private static final String SUBMIT_SSO = """
            (login, secret) => {
                if (top !== window || location.origin !== "https://sso.tyuiu.ru"
                        || location.pathname !== "/realms/master/protocol/openid-connect/auth") return false;
                const email = document.querySelector('input#email[name="email"]:not([type="hidden"])');
                const password = document.querySelector('input#password[name="password"][type="password"]');
                const form = password?.form;
                if (!form || !email || email.form !== form || email.disabled || password.disabled) return false;
                const official = () => {
                    const target = new URL(form.action);
                    return form.method.toLowerCase() === "post"
                        && (!form.target || form.target === "_self")
                        && !target.username && !target.password && target.origin === location.origin
                        && target.pathname === "/realms/master/login-actions/authenticate";
                };
                if (!official()) return false;
                email.value = login;
                email.dispatchEvent(new Event("input", {bubbles: true}));
                if (!official() || password.form !== form) return false;
                password.value = secret;
                if (form.checkValidity()) {
                    HTMLFormElement.prototype.requestSubmit.call(form);
                    return true;
                }
                password.value = "";
                return false;
            }
            """;

    private static final String EDUCON_SSO = """
            (() => {
                if (top !== window || location.origin !== "https://educon2.tyuiu.ru"
                        || location.pathname !== "/login/index.php") return false;
                for (const link of document.querySelectorAll("a[href]")) {
                    const url = new URL(link.href);
                    if (url.origin === location.origin && !url.username && !url.password
                            && url.pathname === "/auth/oauth2/login.php") {
                        location.assign(url.href);
                        return true;
                    }
                }
                return false;
            })()
            """;

    private static final String FIND_RECORD_BOOK = """
            (() => {
                if (top !== window || location.origin !== "https://my.tyuiu.ru") return false;
                const labels = ["электронная зачетная книжка", "зачетная книжка", "электронная зачетка", "зачетка"];
                const normalize = value => value?.toLowerCase().replaceAll("ё", "е").replace(/\\s+/g, " ").trim();
                for (const item of document.querySelectorAll('a[href], button[type="button"], [role="link"], [role="menuitem"]')) {
                    if (!labels.includes(normalize(item.textContent))
                            && !labels.includes(normalize(item.getAttribute("aria-label")))
                            && !labels.includes(normalize(item.getAttribute("title")))) continue;
                    if (item.closest("form")) continue;
                    if (item.matches("a[href]")) {
                        const url = new URL(item.href);
                        if (url.origin !== location.origin || url.username || url.password) continue;
                    }
                    item.click();
                    return true;
                }
                return false;
            })()
            """;

    private final TiuServices.Page page;
    private final Consumer<String> status;
    private final Runnable expireCredentials = () -> clearCredentials();
    private CredentialVault.Entry pendingLogin;
    private boolean educonRedirected;
    private boolean attemptedLogin;
    private boolean searchedRecordBook;
    private boolean mainLoadFailed;

    @SuppressLint("SetJavaScriptEnabled")
    TiuPortal(Context context, TiuServices.Page page, Consumer<String> status) {
        super(context);
        this.page = page;
        this.status = status;
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        var settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false);
        setWebViewClient(new Client());
    }

    void open(CredentialVault.Entry entry) {
        pendingLogin = entry;
        if (entry != null) postDelayed(expireCredentials, 35_000);
        loadUrl(page.url);
    }

    void findRecordBook(boolean announce) {
        Uri current = Uri.parse(getUrl() == null ? "" : getUrl());
        if (!host(current, MY_HOST)) {
            if (announce) status.accept("Сначала войди в «Мой ТИУ».");
            return;
        }
        evaluateJavascript(FIND_RECORD_BOOK, result -> {
            if (announce && !"true".equals(result)) {
                status.accept("Открой электронную зачётную книжку в меню «Мой ТИУ».");
            }
        });
    }

    void close() {
        clearCredentials();
        stopLoading();
        setWebViewClient(new WebViewClient());
        clearCache(true);
        if (getParent() instanceof android.view.ViewGroup parent) parent.removeView(this);
        destroy();
    }

    private void clearCredentials() {
        pendingLogin = null;
        removeCallbacks(expireCredentials);
    }

    static boolean portalOrigin(Uri uri) {
        return host(uri, SSO_HOST) || host(uri, MY_HOST) || host(uri, EDUCON_HOST);
    }

    private static boolean host(Uri uri, String expected) {
        String authority = uri.getEncodedAuthority();
        return "https".equalsIgnoreCase(uri.getScheme()) && authority != null
                && (authority.equalsIgnoreCase(expected) || authority.equalsIgnoreCase(expected + ":443"));
    }

    static boolean loginForm(Uri uri) { return host(uri, SSO_HOST) && SSO_LOGIN.equals(uri.getEncodedPath()); }

    static String credentialScript(CredentialVault.Entry entry) {
        return "(" + SUBMIT_SSO + ")(" + JSONObject.quote(entry.login().strip()) + ", "
                + JSONObject.quote(entry.password()) + ")";
    }

    private final class Client extends WebViewClient {
        @Override public void onPageStarted(WebView view, String address, android.graphics.Bitmap icon) {
            if (!"about:blank".equals(address) && !portalOrigin(Uri.parse(address))) {
                failPage("Этот адрес открывается в браузере.");
                view.stopLoading();
            } else {
                mainLoadFailed = false;
            }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (portalOrigin(request.getUrl())) return false;
            if (request.isForMainFrame()) {
                clearCredentials();
                status.accept("Эта ссылка доступна через браузер.");
            }
            return true;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (portalOrigin(uri) || (!request.isForMainFrame() && host(uri, "storage.tyuiu.ru"))) return null;
            return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Map.of(),
                    new ByteArrayInputStream(new byte[0]));
        }

        @Override public void onPageFinished(WebView view, String address) {
            if (mainLoadFailed || !address.equals(view.getUrl())) return;
            Uri uri = Uri.parse(address);
            if (!portalOrigin(uri)) return;
            status.accept(uri.getHost());
            if (pendingLogin != null && loginForm(uri)) {
                String script = credentialScript(pendingLogin);
                clearCredentials();
                attemptedLogin = true;
                evaluateJavascript(script, result -> {
                    if (!"true".equals(result)) status.accept("Официальная форма открыта. Введи данные на странице ТИУ.");
                });
            } else if (pendingLogin != null && host(uri, EDUCON_HOST)
                    && "/login/index.php".equals(uri.getEncodedPath()) && !educonRedirected) {
                educonRedirected = true;
                evaluateJavascript(EDUCON_SSO, result -> {
                    if (!"true".equals(result)) {
                        clearCredentials();
                        status.accept("Выбери вход через учётную запись ТИУ на странице Эдукона.");
                    }
                });
            } else if (attemptedLogin && host(uri, SSO_HOST) && SSO_ACTION.equals(uri.getEncodedPath())) {
                status.accept("Проверь сообщение на странице входа ТИУ.");
            } else if (host(uri, MY_HOST) || (host(uri, EDUCON_HOST) && !"/login/index.php".equals(uri.getEncodedPath()))) {
                clearCredentials();
                if (page == TiuServices.Page.RECORD_BOOK && host(uri, MY_HOST) && !searchedRecordBook) {
                    searchedRecordBook = true;
                    findRecordBook(true);
                }
            }
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                if (!mainLoadFailed) failPage("Не удалось открыть страницу ТИУ.");
            }
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            if (portalOrigin(Uri.parse(error.getUrl()))) failPage("Не удалось подтвердить сертификат сайта ТИУ.");
        }
    }

    private void failPage(String message) {
        mainLoadFailed = true;
        clearCredentials();
        status.accept(message);
    }
}
