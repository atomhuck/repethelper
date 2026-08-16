package ru.repethelper.web;

import java.beans.PropertyEditorSupport;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/** Allows formatted values such as "12 000" even when JavaScript is unavailable. */
@ControllerAdvice
public class MoneyInputBindingAdvice {
    private final MoneyInputParser parser;

    public MoneyInputBindingAdvice(MoneyInputParser parser) { this.parser = parser; }

    @InitBinder
    void bindFormattedRubles(WebDataBinder binder) {
        PropertyEditorSupport editor = new PropertyEditorSupport() {
            @Override public void setAsText(String text) { setValue(parser.parseNullable(text)); }
        };
        binder.registerCustomEditor(Integer.class, "priceRubles", editor);
        binder.registerCustomEditor(Integer.class, "subscriptionTotalRubles", editor);
    }
}
