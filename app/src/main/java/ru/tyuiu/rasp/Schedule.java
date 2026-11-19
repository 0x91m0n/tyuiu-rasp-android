package ru.tyuiu.rasp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class Schedule {
    static final ZoneId ZONE = ZoneId.of("Asia/Yekaterinburg");
    static final Locale RU = Locale.forLanguageTag("ru-RU");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", RU);
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", RU);
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final Schedule EMPTY = new Schedule("", "", List.of(), false);

    record Lesson(LocalDateTime start, LocalDateTime end, String title, String type,
                  String room, String teacher, String subgroup) {
        Instant starts() { return start.atZone(ZONE).toInstant(); }
        Instant ends() { return end.atZone(ZONE).toInstant(); }
        String time() { return start.format(TIME) + "–" + end.format(TIME); }
        String key() { return start + "|" + title + "|" + subgroup; }
        String meta() {
            var parts = new ArrayList<String>();
            if (!type.isBlank()) parts.add(type);
            if (!room.isBlank()) parts.add(room);
            if (!teacher.isBlank()) parts.add(teacher);
            if (!subgroup.isBlank()) parts.add("подгр. " + subgroup);
            return String.join(" · ", parts);
        }
        int progress(Instant now) {
            long elapsed = Duration.between(starts(), now).toMillis();
            long duration = Duration.between(starts(), ends()).toMillis();
            return (int) Math.min(100, Math.max(0, elapsed * 100 / duration));
        }
    }
    record Group(String name, URL url, String details) {}

    final String group;
    final String source;
    final List<Lesson> lessons;
    final boolean demo;

    Schedule(String group, String source, List<Lesson> lessons, boolean demo) {
        this.group = group;
        this.source = source;
        var sorted = new ArrayList<>(new LinkedHashSet<>(lessons));
        sorted.sort(Comparator.comparing(Lesson::start).thenComparing(Lesson::title).thenComparing(Lesson::subgroup));
        this.lessons = List.copyOf(sorted);
        this.demo = demo;
    }

    static LocalDate today() { return LocalDate.now(ZONE); }

    Instant lastEnd(LocalDate day, String subgroup) {
        Instant last = null;
        for (Lesson lesson : day(day, subgroup)) {
            if (last == null || lesson.ends().isAfter(last)) last = lesson.ends();
        }
        return last;
    }

    LocalDate defaultDay(Instant now, String subgroup) {
        LocalDate today = now.atZone(ZONE).toLocalDate();
        Instant last = lastEnd(today, subgroup);
        return last != null && !now.isBefore(last.plusSeconds(7200)) ? today.plusDays(1) : today;
    }

    List<Lesson> day(LocalDate date, String subgroup) {
        var out = new ArrayList<Lesson>();
        for (Lesson lesson : lessons) {
            if (lesson.start.toLocalDate().equals(date) && matches(lesson, subgroup)) out.add(lesson);
        }
        return out;
    }

    static boolean matches(Lesson lesson, String subgroup) {
        if (subgroup.isBlank() || lesson.subgroup.isBlank()) return true;
        for (String part : lesson.subgroup.split("[,/;]")) {
            if (part.trim().equalsIgnoreCase(subgroup)) return true;
        }
        return false;
    }

    List<String> subgroups() {
        var groups = new java.util.TreeSet<String>(Comparator.comparingInt(String::length).thenComparing(s -> s));
        for (Lesson lesson : lessons) {
            for (String item : lesson.subgroup.split("[,/;]")) {
                if (!item.isBlank()) groups.add(item.trim());
            }
        }
        return new ArrayList<>(groups);
    }

    Lesson next(Instant now, String subgroup) {
        for (Lesson lesson : lessons) {
            if (matches(lesson, subgroup) && lesson.ends().isAfter(now)) return lesson;
        }
        return null;
    }

    static String field(JSONObject value, String name) {
        return value.isNull(name) ? "" : value.optString(name).trim();
    }

    static Schedule parse(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        if (root.getInt("version") != 1) throw new JSONException("Версия расписания не поддерживается");
        String group = field(root, "group");
        if (group.isBlank() || group.length() > 120) throw new JSONException("Проверьте название группы");
        JSONArray array = root.getJSONArray("lessons");
        if (array.length() > 5000) throw new JSONException("Слишком много занятий");
        var lessons = new ArrayList<Lesson>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            LocalDateTime start = LocalDateTime.parse(item.getString("start"));
            LocalDateTime end = LocalDateTime.parse(item.getString("end"));
            String title = field(item, "title");
            if (title.isBlank() || title.length() > 300 || !end.isAfter(start)
                    || Duration.between(start, end).toHours() >= 24) {
                throw new JSONException("Проверьте название и время занятия " + (i + 1));
            }
            lessons.add(new Lesson(start, end, title, field(item, "type"), field(item, "room"),
                    field(item, "teacher"), field(item, "subgroup")));
        }
        return new Schedule(group, field(root, "source"), lessons, root.optBoolean("demo"));
    }

    JSONObject json() {
        try {
            JSONArray items = new JSONArray();
            for (Lesson l : lessons) {
                items.put(new JSONObject().put("start", l.start.toString()).put("end", l.end.toString())
                        .put("title", l.title).put("room", l.room).put("teacher", l.teacher)
                        .put("type", l.type).put("subgroup", l.subgroup));
            }
            return new JSONObject().put("version", 1).put("group", group).put("source", source)
                    .put("demo", demo).put("lessons", items);
        } catch (JSONException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static List<Group> groups(URL url) throws Exception {
        var result = new ArrayList<Group>();
        String raw = download(url);
        if (OfficialTiu.catalog(url)) {
            result.addAll(OfficialTiu.groups(raw));
        } else {
            JSONObject catalog = new JSONObject(raw);
            if (catalog.getInt("version") != 1) throw new IOException("Версия каталога не поддерживается");
            JSONArray entries = catalog.getJSONArray("groups");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String name = field(entry, "name");
                if (name.isBlank()) throw new IOException("В каталоге есть группа без названия");
                result.add(new Group(name, https(new URL(url, entry.getString("schedule")).toString()),
                        field(entry, "details")));
            }
        }
        result.sort(Comparator.comparing(Group::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Group::details));
        return result;
    }

    static Schedule load(URL url) throws Exception {
        String raw = download(url);
        return OfficialTiu.group(url) ? OfficialTiu.schedule(raw, url, today()) : parse(raw);
    }

    static URL https(String input) throws IOException {
        URL url = new URL(input.trim());
        if (!"https".equals(url.getProtocol()) || url.getHost().isBlank() || url.getUserInfo() != null) {
            throw new IOException("Нужна HTTPS-ссылка");
        }
        return url;
    }

    static String download(URL url) throws IOException {
        HttpURLConnection request = (HttpURLConnection) url.openConnection();
        request.setConnectTimeout(7000);
        request.setReadTimeout(10000);
        request.setInstanceFollowRedirects(false);
        request.setRequestProperty("Accept", OfficialTiu.catalog(url) || OfficialTiu.group(url)
                ? "text/html" : "application/json");
        request.setRequestProperty("User-Agent", "PairsTIU/0.1 (Android)");
        try {
            int status = request.getResponseCode();
            if (status != 200) throw new IOException("Источник вернул HTTP " + status);
            try (InputStream stream = request.getInputStream()) { return read(stream); }
        } finally {
            request.disconnect();
        }
    }

    static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Не удалось открыть файл");
        var output = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        int bytes;
        while ((bytes = input.read(buffer)) != -1) {
            if (output.size() + bytes > MAX_BYTES) throw new IOException("Файл больше 4 МБ");
            output.write(buffer, 0, bytes);
        }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    static Schedule example() {
        LocalDate date = today();
        return new Schedule("ДЕМО-1", "Демонстрационное расписание", List.of(
                new Lesson(date.atTime(8, 0), date.atTime(9, 35), "Высшая математика", "Лекция", "Ауд. 304", "", ""),
                new Lesson(date.atTime(9, 45), date.atTime(11, 20), "Программирование", "Практика", "Ауд. 216", "", ""),
                new Lesson(date.atTime(13, 45), date.atTime(15, 20), "Инженерная графика", "Лабораторная", "Ауд. 510", "", ""),
                new Lesson(date.plusDays(1).atTime(9, 45), date.plusDays(1).atTime(11, 20),
                        "Английский язык", "Практика", "Ауд. 121", "", "")
        ), true);
    }
}
