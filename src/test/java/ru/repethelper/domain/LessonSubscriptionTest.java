package ru.repethelper.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LessonSubscriptionTest {
    private final User teacher = new User("teacher-subscription", "hash", "Преподаватель", Role.TEACHER);
    private final User student = new User("student-subscription", "hash", "Ученик", Role.STUDENT);

    @Test
    void distributesRemainderAcrossFirstCreditsWithoutLosingRubles() {
        LessonSubscription subscription = new LessonSubscription(teacher, student, 6, 10_000, Instant.EPOCH);

        assertThat(subscription.getCredits()).extracting(LessonSubscriptionCredit::getAmountRubles)
                .containsExactly(1_667, 1_667, 1_667, 1_667, 1_666, 1_666);
        assertThat(subscription.getCredits().stream().mapToInt(LessonSubscriptionCredit::getAmountRubles).sum())
                .isEqualTo(10_000);
    }

    @Test
    void distributesDivisibleAmountEqually() {
        LessonSubscription subscription = new LessonSubscription(teacher, student, 8, 12_000, Instant.EPOCH);
        assertThat(subscription.getCredits()).extracting(LessonSubscriptionCredit::getAmountRubles)
                .containsOnly(1_500).hasSize(8);
    }

    @Test
    void validatesPackageBounds() {
        assertThatThrownBy(() -> new LessonSubscription(teacher, student, 0, 1, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LessonSubscription(teacher, student, 101, 101, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LessonSubscription(teacher, student, 10, 9, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LessonSubscription(teacher, student, 1, 1_000_001, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
