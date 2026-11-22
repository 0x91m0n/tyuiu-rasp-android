package ru.tyuiu.rasp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.FormElement;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import javax.net.ssl.HttpsURLConnection;

final class TiuRecordBook {
    static final String URL = "https://my.tyuiu.ru/record-book";
    static final String MY_HOST = "my.tyuiu.ru";
    static final String SSO_HOST = "sso.tyuiu.ru";
    private static final String LOGIN_PAGE = "/realms/master/protocol/openid-connect/auth";
    private static final String LOGIN_ACTION = "/realms/master/login-actions/authenticate";
    private static final int MAX_BODY = 4 * 1024 * 1024;

    record Grade(String subject, String exam, String mark, String points, String date) {}
    record Semester(String name, List<Grade> grades) {}
    record Book(String group, boolean active, List<Semester> semesters) {}

    static final class InteractiveLogin extends IOException {
        InteractiveLogin() { super("The TIU login requires user interaction"); }
    }

    private TiuRecordBook() {}

    static List<Book> load(CredentialVault.Entry entry) throws IOException, JSONException {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        var book = URI.create(URL);
        var page = book;
        boolean submitted = false;
        byte[] form = null;
        try {
            for (int hop = 0; hop < 10; hop++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Request interrupted");
                if (!allowedPage(page)) throw new InteractiveLogin();
                var connection = (HttpsURLConnection) page.toURL().openConnection();
                try {
                    connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);
                    connection.setConnectTimeout(10_000);
                    connection.setReadTimeout(12_000);
                    connection.setRequestProperty("Accept", "text/html, application/xhtml+xml");
                    connection.setRequestProperty("Cache-Control", "no-store");
                    for (var header : cookies.get(page, Map.of()).entrySet()) {
                        for (String value : header.getValue()) connection.addRequestProperty(header.getKey(), value);
                    }
                    if (form != null) {
                        connection.setRequestMethod("POST");
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Origin", "https://" + SSO_HOST);
                        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        connection.setFixedLengthStreamingMode(form.length);
                        try (var output = connection.getOutputStream()) {
                            output.write(form);
                        } finally {
                            Arrays.fill(form, (byte) 0);
                            form = null;
                        }
                    }
                    int status = connection.getResponseCode();
                    cookies.put(page, connection.getHeaderFields());
                    if (status == 301 || status == 302 || status == 303) {
                        URI next = resolve(page, connection.getHeaderField("Location"));
                        // The callback sets the portal session. Go straight to the book if
                        // the portal sends the browser back to its home page.
                        page = "/sso/callback".equals(page.getRawPath()) && origin(next, MY_HOST) ? book : next;
                    } else if (status == 200 && origin(page, SSO_HOST)) {
                        if (submitted || !LOGIN_PAGE.equals(page.getRawPath())) throw new InteractiveLogin();
                        String html = readHtml(connection);
                        var login = loginForm(html, page, entry);
                        form = login.body();
                        page = login.action();
                        submitted = true;
                    } else if (status == 200 && origin(page, MY_HOST) && book.getRawPath().equals(page.getRawPath())) {
                        return parse(readHtml(connection));
                    } else if (status >= 400 && status < 500) {
                        throw new InteractiveLogin();
                    } else {
                        throw new IOException("Unexpected TIU response");
                    }
                } finally {
                    connection.disconnect();
                }
            }
            throw new InteractiveLogin();
        } finally {
            if (form != null) Arrays.fill(form, (byte) 0);
            cookies.getCookieStore().removeAll();
        }
    }

    private static String readHtml(HttpsURLConnection connection) throws IOException {
        String type = connection.getContentType();
        if (type == null || !(type.startsWith("text/html") || type.startsWith("application/xhtml+xml"))) {
            throw new IOException("Expected a TIU HTML page");
        }
        try (var input = connection.getInputStream()) {
            var bytes = input.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) throw new IOException("TIU response exceeds limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static boolean origin(URI url, String host) {
        return url != null && "https".equalsIgnoreCase(url.getScheme())
                && host.equalsIgnoreCase(url.getHost()) && url.getRawUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443) && url.getRawFragment() == null;
    }

    private static boolean allowedPage(URI url) {
        if (origin(url, MY_HOST)) {
            return switch (url.getRawPath()) {
                case "/record-book", "/sso/redirect", "/sso/callback" -> true;
                default -> false;
            };
        }
        return origin(url, SSO_HOST)
                && (LOGIN_PAGE.equals(url.getRawPath()) || LOGIN_ACTION.equals(url.getRawPath()));
    }

    private static URI resolve(URI page, String location) throws InteractiveLogin {
        if (location == null || location.isBlank()) throw new InteractiveLogin();
        try {
            return page.resolve(location);
        } catch (IllegalArgumentException malformed) {
            throw new InteractiveLogin();
        }
    }

    record LoginForm(URI action, byte[] body) {}

    static LoginForm loginForm(String html, URI page, CredentialVault.Entry entry) throws InteractiveLogin {
        if (!origin(page, SSO_HOST) || !LOGIN_PAGE.equals(page.getRawPath())) throw new InteractiveLogin();
        var password = Jsoup.parse(html).selectFirst("input#password[name=password][type=password]:not([disabled])");
        if (password == null || !(password.closest("form") instanceof FormElement form)
                || !"post".equalsIgnoreCase(form.attr("method"))
                || form.selectFirst("input#email[name=email]:not([disabled])") == null
                || form.selectFirst("input#username[name=username][type=hidden]:not([disabled])") == null) {
            throw new InteractiveLogin();
        }
        URI action = resolve(page, form.attr("action"));
        if (!origin(action, SSO_HOST) || !LOGIN_ACTION.equals(action.getRawPath())) throw new InteractiveLogin();

        String login = entry.login().strip();
        String username = login;
        if (!login.contains("@")) {
            var domain = form.selectFirst("select#domainSelect option[selected]");
            if (domain == null || !"@std.tyuiu.ru".equals(domain.val())) throw new InteractiveLogin();
            username += domain.val();
        }

        var body = new StringJoiner("&");
        for (var field : form.formData()) {
            String value = switch (field.key()) {
                case "email" -> login;
                case "username" -> username;
                case "password" -> entry.password();
                default -> field.value();
            };
            if (!"rememberMe".equals(field.key())) {
                body.add(URLEncoder.encode(field.key(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        return new LoginForm(action, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    static List<Book> parse(String html) throws JSONException {
        var root = Jsoup.parse(html).selectFirst("#app[data-page]");
        if (root == null) throw new JSONException("The record book page data is missing");
        var page = new JSONObject(root.attr("data-page"));
        if (!"Web/RecordBook/Index".equals(page.optString("component"))) {
            throw new JSONException("Expected the record book component");
        }
        return parseRecords(page.getJSONObject("props").get("records"));
    }

    static List<Book> parseRecords(Object records) throws JSONException {
        var books = new ArrayList<Book>();
        for (JSONObject record : objects(records)) {
            var terms = record.optJSONObject("records");
            var semesters = new ArrayList<Semester>();
            if (terms != null) {
                for (var names = terms.keys(); names.hasNext(); ) {
                    String name = names.next();
                    var grades = new ArrayList<Grade>();
                    for (JSONObject row : objects(terms.get(name))) {
                        grades.add(new Grade(value(row, "discipline"), value(row, "exam"), value(row, "mark"),
                                value(row, "points"), value(row, "perform_date")));
                    }
                    semesters.add(new Semester(name, List.copyOf(grades)));
                }
            } else if (!(record.opt("records") instanceof JSONArray empty && empty.length() == 0)) {
                throw new JSONException("Expected record book semesters");
            }
            books.add(new Book(value(record, "group"), "активный".equalsIgnoreCase(value(record, "status")),
                    List.copyOf(semesters)));
        }
        books.sort(Comparator.comparing(Book::active).reversed());
        return List.copyOf(books);
    }

    static JSONArray recordsJson(List<Book> books) throws JSONException {
        var records = new JSONArray();
        for (Book book : books) {
            var terms = new JSONObject();
            for (Semester semester : book.semesters()) {
                var rows = new JSONArray();
                for (Grade grade : semester.grades()) {
                    rows.put(new JSONObject().put("discipline", grade.subject()).put("exam", grade.exam())
                            .put("mark", grade.mark()).put("points", grade.points()).put("perform_date", grade.date()));
                }
                terms.put(semester.name(), rows);
            }
            records.put(new JSONObject().put("group", book.group()).put("records", terms)
                    .put("status", book.active() ? "активный" : ""));
        }
        return records;
    }

    private static List<JSONObject> objects(Object data) throws JSONException {
        var items = new ArrayList<JSONObject>();
        if (data instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) items.add(array.getJSONObject(i));
        } else if (data instanceof JSONObject map) {
            for (var keys = map.keys(); keys.hasNext(); ) items.add(map.getJSONObject(keys.next()));
        } else {
            throw new JSONException("Expected a collection of record book entries");
        }
        return items;
    }

    private static String value(JSONObject object, String name) throws JSONException {
        if (object.isNull(name)) return "";
        Object value = object.get(name);
        if (value instanceof String || value instanceof Number) return value.toString().strip();
        throw new JSONException("Invalid record book field");
    }
}
