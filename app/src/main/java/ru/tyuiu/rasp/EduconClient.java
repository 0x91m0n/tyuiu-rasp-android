package ru.tyuiu.rasp;

import android.text.Html;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import javax.net.ssl.HttpsURLConnection;

final class EduconClient {
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final String REST = "/webservice/rest/server.php";

    record Grade(int courseId, String course, String score) {}

    static final class SignInFailed extends IOException {}

    private EduconClient() {}

    static List<Grade> grades(CredentialVault.Entry credential) throws IOException, JSONException {
        Object reply = post("/login/token.php", Map.of("service", "moodle_mobile_app",
                "username", credential.login().strip(), "password", credential.password()));
        if (!(reply instanceof JSONObject auth)) throw new IOException("Unexpected Educon login response");
        String token = auth.optString("token", "");
        if (token.isBlank()) throw new SignInFailed();
        var marks = (JSONObject) call(token, "gradereport_overview_get_course_grades", Map.of());
        JSONArray rows = marks.getJSONArray("grades");
        if (rows.length() == 0) return List.of();
        var profile = (JSONObject) call(token, "core_webservice_get_site_info", Map.of());
        long user = profile.getLong("userid");
        if (user <= 0) throw new IOException("Missing Educon account identifier");
        var courses = (JSONArray) call(token, "core_enrol_get_users_courses", Map.of("userid", Long.toString(user)));
        return decode(rows, courses);
    }

    static List<Grade> decode(JSONArray marks, JSONArray courses) throws JSONException {
        var titles = new HashMap<Integer, String>();
        for (int i = 0; i < courses.length(); i++) {
            var course = courses.getJSONObject(i);
            titles.put(course.getInt("id"), plain(course.optString("fullname", "")));
        }
        var result = new ArrayList<Grade>();
        for (int i = 0; i < marks.length(); i++) {
            var row = marks.getJSONObject(i);
            int id = row.getInt("courseid");
            if (id <= 0) continue;
            String name = titles.getOrDefault(id, "");
            String mark = row.isNull("grade") ? "" : plain(row.optString("grade", ""));
            result.add(new Grade(id, name.isBlank() ? "Курс № " + id : name,
                    mark.isBlank() || mark.equals("-") ? "—" : mark));
        }
        return result;
    }

    private static String plain(String value) {
        return Html.fromHtml(value, Html.FROM_HTML_MODE_COMPACT).toString().strip();
    }

    private static Object call(String token, String function, Map<String, String> params) throws IOException, JSONException {
        var form = new LinkedHashMap<>(params);
        form.put("wstoken", token);
        form.put("wsfunction", function);
        form.put("moodlewsrestformat", "json");
        Object reply = post(REST, form);
        if (reply instanceof JSONObject object && (object.has("exception") || object.has("errorcode"))) {
            if ("invalidtoken".equals(object.optString("errorcode"))) throw new SignInFailed();
            throw new IOException("Educon web service rejected the request");
        }
        return reply;
    }

    private static Object post(String path, Map<String, String> fields) throws IOException, JSONException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        var connection = (HttpsURLConnection) URI.create(TiuServices.EDUCON + path).toURL().openConnection();
        var form = new StringJoiner("&");
        fields.forEach((key, value) -> form.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        byte[] encoded = form.toString().getBytes(StandardCharsets.UTF_8);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (var output = connection.getOutputStream()) {
                output.write(encoded);
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new SignInFailed();
            if (status != 200) throw new IOException("Educon HTTP status " + status);
            try (var input = connection.getInputStream()) {
                byte[] content = input.readNBytes(LIMIT + 1);
                if (content.length > LIMIT) throw new IOException("Educon response is too large");
                return new JSONTokener(new String(content, StandardCharsets.UTF_8)).nextValue();
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
            connection.disconnect();
        }
    }
}
