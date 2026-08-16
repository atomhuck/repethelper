package ru.repethelper.web;

import org.springframework.stereotype.Component;

/** Parses money entered by people, while keeping all persistence values integer rubles. */
@Component
public class MoneyInputParser {
    public Integer parseNullable(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.replace(" ", "").replace("\u00a0", "").replace("\u202f", "");
        if (!normalized.matches("[0-9]+"))
            throw new IllegalArgumentException("Введите сумму целым числом без копеек");
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Сумма слишком большая");
        }
    }

    public int parseRequired(String raw, String fieldName) {
        Integer value = parseNullable(raw);
        if (value == null) throw new IllegalArgumentException("Укажите " + fieldName);
        return value;
    }
}
