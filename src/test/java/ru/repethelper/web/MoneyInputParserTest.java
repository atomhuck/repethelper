package ru.repethelper.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyInputParserTest {
    private final MoneyInputParser parser = new MoneyInputParser();

    @Test
    void acceptsGroupedWholeRubles() {
        assertThat(parser.parseNullable("12000")).isEqualTo(12_000);
        assertThat(parser.parseNullable("12 000")).isEqualTo(12_000);
        assertThat(parser.parseNullable("12\u00a0000")).isEqualTo(12_000);
        assertThat(parser.parseNullable("12\u202f000")).isEqualTo(12_000);
        assertThat(parser.parseNullable(" ")).isNull();
    }

    @Test
    void rejectsAnythingExceptWholeDigits() {
        for (String value : new String[] {"12.5", "-12", "1e4", "12abc", "99999999999999999999"}) {
            assertThatThrownBy(() -> parser.parseNullable(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
