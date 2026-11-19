package ru.tyuiu.rasp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

final class OfficialTiu {
    static final String HOME = "https://timetable.tyuiu.ru/";
    private static final Pattern PAGE = Pattern.compile(
            "<script\\b[^>]*\\bdata-page\\s*=\\s*[\"']app[\"'][^>]*>(.*?)</script\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_PATH = Pattern.compile("/schedule/group/[0-9]+");

    private record Slot(LocalTime start, LocalTime end) {}
    private record Template(int weekday, boolean even, Slot slot, String title, String type,
                            String room, String teacher, String subgroup) {
        Schedule.Lesson on(LocalDate date) {
            return new Schedule.Lesson(date.atTime(slot.start), date.atTime(slot.end),
                    title, type, room, teacher, subgroup);
        }
    }

    static boolean catalog(URL url) {
        return isOfficial(url) && url.getQuery() == null && (url.getPath().isEmpty() || "/".equals(url.getPath()));
    }

    static boolean group(URL url) {
        return isOfficial(url) && url.getQuery() == null && GROUP_PATH.matcher(url.getPath()).matches();
    }

    private static boolean isOfficial(URL url) {
        return "https".equals(url.getProtocol()) && "timetable.tyuiu.ru".equalsIgnoreCase(url.getHost())
                && (url.getPort() == -1 || url.getPort() == 443);
    }

    private static JSONObject props(String html, String component) throws Exception {
        var marker = PAGE.matcher(html);
        if (!marker.find()) throw new IOException("На сайте ТИУ изменился формат расписания");
        JSONObject page = new JSONObject(marker.group(1));
        JSONObject props = page.getJSONObject("props");
        if (!component.equals(page.getString("component")) || !"Public_schedule_TIU".equals(props.getString("name"))) {
            throw new IOException("Получена другая страница сайта ТИУ");
        }
        return props;
    }

    static List<Schedule.Group> groups(String html) throws Exception {
        JSONArray groups = props(html, "Welcome").getJSONArray("groups");
        var result = new ArrayList<Schedule.Group>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            String name = Schedule.field(group, "name");
            URL target = Schedule.https(group.getString("url"));
            if (name.isBlank() || !group(target) || !target.getPath().endsWith("/" + group.getLong("id"))) continue;
            String form = switch (Schedule.field(group, "studyForm")) {
                case "full_time" -> "Очная";
                case "part_time" -> "Заочная";
                default -> "";
            };
            String institute = Schedule.field(group, "institute");
            String details = institute.isBlank() || form.isBlank() ? institute + form : institute + " · " + form;
            result.add(new Schedule.Group(name, target, details));
        }
        if (result.isEmpty()) throw new IOException("Сайт ТИУ не вернул список групп");
        return result;
    }

    static Schedule schedule(String html, URL url, LocalDate today) throws Exception {
        JSONObject props = props(html, "Schedule");
        JSONObject entity = props.getJSONObject("entity");
        String name = Schedule.field(entity, "name");
        if (!"group".equals(props.getString("entityType")) || name.isBlank()
                || !url.getPath().endsWith("/" + entity.getLong("id"))) {
            throw new IOException("На сайте ТИУ открылась другая группа");
        }
        var slots = new HashMap<Long, Slot>();
        JSONArray slotData = props.getJSONArray("slots");
        for (int i = 0; i < slotData.length(); i++) {
            JSONObject entry = slotData.getJSONObject(i);
            LocalTime start = LocalTime.parse(entry.getString("start"));
            LocalTime end = LocalTime.parse(entry.getString("end"));
            if (!end.isAfter(start)) throw new IOException("Некорректное время пары на сайте ТИУ");
            slots.put(entry.getLong("id"), new Slot(start, end));
        }
        var lessons = new ArrayList<Schedule.Lesson>();
        var templates = new ArrayList<Template>();
        var datedWeeks = new HashSet<LocalDate>();
        JSONArray weeks = props.getJSONArray("weeks");
        for (int i = 0; i < weeks.length(); i++) datedWeeks.add(monday(LocalDate.parse(weeks.getString(i))));
        JSONArray entries = props.getJSONArray("lessons");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            Slot slot = slots.get(entry.getLong("slotId"));
            String title = Schedule.field(entry, "subject");
            if (slot == null || title.isBlank()) throw new IOException("Не удалось прочитать пару на сайте ТИУ");
            int subgroup = entry.optInt("subgroup");
            String date = Schedule.field(entry, "date");
            Template lesson = new Template(entry.getInt("dayNumber"), date.isBlank() && entry.getBoolean("isEven"),
                    slot, title,
                    Schedule.field(entry, "lessonType"), Schedule.field(entry, "room"), Schedule.field(entry, "teacher"),
                    subgroup > 0 ? Integer.toString(subgroup) : "");
            if (date.isBlank()) {
                templates.add(lesson);
            } else {
                LocalDate actual = LocalDate.parse(date);
                lessons.add(lesson.on(actual));
                datedWeeks.add(monday(actual));
            }
        }
        LocalDate september = LocalDate.of(today.getYear(), 9, 1);
        if (today.isBefore(september)) september = september.minusYears(1);
        LocalDate from = today.minusDays(14).isAfter(september) ? today.minusDays(14) : september;
        LocalDate until = today.plusDays(28).isBefore(september.plusYears(1)) ? today.plusDays(28) : september.plusYears(1).minusDays(1);
        for (LocalDate date = from; !date.isAfter(until) && !templates.isEmpty(); date = date.plusDays(1)) {
            if (datedWeeks.contains(monday(date))) continue;
            boolean even = evenWeek(date);
            for (Template lesson : templates) {
                if (lesson.weekday == date.getDayOfWeek().getValue() && lesson.even == even) lessons.add(lesson.on(date));
            }
        }
        String source = templates.isEmpty() ? "Сайт ТИУ" : "Сайт ТИУ · типовые недели";
        return new Schedule(name, source, lessons, false);
    }

    static boolean evenWeek(LocalDate date) {
        LocalDate september = LocalDate.of(date.getYear(), 9, 1);
        if (date.isBefore(september)) september = september.minusYears(1);
        return ChronoUnit.WEEKS.between(monday(september), monday(date)) % 2 != 0;
    }

    private static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
