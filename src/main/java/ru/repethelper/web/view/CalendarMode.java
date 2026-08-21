package ru.repethelper.web.view;

public enum CalendarMode {
    WEEK,
    MONTH;

    public static CalendarMode from(String value) {
        return "month".equalsIgnoreCase(value) ? MONTH : WEEK;
    }

    public String queryValue() {
        return name().toLowerCase();
    }
}
