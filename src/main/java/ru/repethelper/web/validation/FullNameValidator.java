package ru.repethelper.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class FullNameValidator implements ConstraintValidator<FullName, String> {
    private static final Pattern WORD = Pattern.compile("(?U)^\\p{L}+(?:[-'’]\\p{L}+)*$");
    private static final Pattern REPEATED_CHARACTER = Pattern.compile("(?iu)^(.)\\1+$");
    private static final Set<String> PLACEHOLDERS = Set.of(
            "test", "тест", "asdf", "qwerty", "admin", "none", "null", "unknown", "безимени");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return true; // @NotBlank produces the clearer required-field message.
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 2) return false;
        String first = parts[0];
        String last = parts[1];
        return validPart(first) && validPart(last) && !first.equalsIgnoreCase(last);
    }

    private boolean validPart(String part) {
        String normalized = part.toLowerCase(Locale.ROOT).replaceAll("[-'’]", "");
        return part.length() >= 2 && part.length() <= 40
                && WORD.matcher(part).matches()
                && !REPEATED_CHARACTER.matcher(normalized).matches()
                && !PLACEHOLDERS.contains(normalized);
    }
}
