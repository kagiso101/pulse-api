package pulse_api.service;

import org.springframework.stereotype.Component;
import pulse_api.dto.ProspectDtos.ProspectUpsert;
import pulse_api.entity.Enums;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 4180-ish CSV for the prospect import (contract §2.5): quoted fields, doubled quotes, commas
 * and newlines inside quotes, CRLF or LF. Header names are case-insensitive; {@code website} is
 * an alias of {@code has_website}. Bad rows are reported, never silently dropped.
 */
@Component
public class ProspectCsvParser {

    public record Parsed(List<ProspectUpsert> rows, List<String> errors) {}

    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("name", "name"), Map.entry("business", "business"), Map.entry("company", "business"),
            Map.entry("phone", "phone"), Map.entry("tel", "phone"), Map.entry("area", "area"),
            Map.entry("has_website", "has_website"), Map.entry("website", "has_website"),
            Map.entry("has website", "has_website"), Map.entry("status", "status"),
            Map.entry("next_action", "next_action"), Map.entry("next action", "next_action"),
            Map.entry("next_action_date", "next_action_date"), Map.entry("next action date", "next_action_date"),
            Map.entry("notes", "notes"), Map.entry("note", "notes"));

    public Parsed parse(String csv) {
        List<String> errors = new ArrayList<>();
        List<ProspectUpsert> rows = new ArrayList<>();
        List<List<String>> records = split(csv == null ? "" : csv.replace("﻿", ""));
        if (records.isEmpty()) {
            errors.add("CSV is empty");
            return new Parsed(rows, errors);
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = records.get(0);
        for (int i = 0; i < header.size(); i++) {
            String key = HEADER_ALIASES.get(header.get(i).trim().toLowerCase(Locale.ROOT));
            if (key != null) {
                columns.putIfAbsent(key, i);
            }
        }
        if (!columns.containsKey("name")) {
            errors.add("CSV needs a 'name' column");
            return new Parsed(rows, errors);
        }
        for (int line = 1; line < records.size(); line++) {
            List<String> rec = records.get(line);
            if (rec.stream().allMatch(String::isBlank)) {
                continue;
            }
            int rowNumber = line + 1;
            String name = cell(rec, columns, "name");
            if (name == null) {
                errors.add("Row " + rowNumber + ": name is required");
                continue;
            }
            Enums.ProspectStatus status = Enums.ProspectStatus.to_contact;
            String rawStatus = cell(rec, columns, "status");
            if (rawStatus != null) {
                try {
                    status = Enums.ProspectStatus.valueOf(rawStatus.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
                } catch (IllegalArgumentException bad) {
                    errors.add("Row " + rowNumber + ": unknown status '" + rawStatus + "'");
                    continue;
                }
            }
            LocalDate nextActionDate = null;
            String rawDate = cell(rec, columns, "next_action_date");
            if (rawDate != null) {
                try {
                    nextActionDate = LocalDate.parse(rawDate.trim());
                } catch (DateTimeParseException bad) {
                    errors.add("Row " + rowNumber + ": next_action_date '" + rawDate + "' is not YYYY-MM-DD");
                    continue;
                }
            }
            rows.add(new ProspectUpsert(name, cell(rec, columns, "business"), cell(rec, columns, "phone"),
                    cell(rec, columns, "area"), bool(cell(rec, columns, "has_website")), status,
                    cell(rec, columns, "next_action"), nextActionDate, cell(rec, columns, "notes")));
        }
        return new Parsed(rows, errors);
    }

    private static String cell(List<String> rec, Map<String, Integer> columns, String key) {
        Integer idx = columns.get(key);
        if (idx == null || idx >= rec.size()) {
            return null;
        }
        String v = rec.get(idx).trim();
        return v.isEmpty() ? null : v;
    }

    static Boolean bool(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1", "has", "ja" -> true;
            case "false", "no", "n", "0", "none", "nee" -> false;
            default -> null;
        };
    }

    /** Splits into records of fields, honouring quotes. */
    static List<List<String>> split(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < csv.length()) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(field.toString());
                field.setLength(0);
                records.add(fields);
                fields = new ArrayList<>();
            } else {
                field.append(c);
            }
            i++;
        }
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            records.add(fields);
        }
        return records;
    }
}
