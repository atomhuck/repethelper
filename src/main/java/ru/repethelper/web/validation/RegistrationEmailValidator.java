package ru.repethelper.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RegistrationEmailValidator implements ConstraintValidator<RegistrationEmail, String> {
    private static final Pattern LOCAL_PART = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{2,59})$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return true; // @NotBlank owns the empty-field error.
        String email = value.trim();
        if (email.length() > 254 || email.chars().anyMatch(Character::isWhitespace)) return false;
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@')) return false;
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        if (local.length() > 64 || !LOCAL_PART.matcher(local).matches()) return false;
        try {
            return DOMAIN.matcher(IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT)).matches();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
