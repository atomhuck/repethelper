package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "lesson_subscriptions")
public class LessonSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @Column(name = "total_lessons", nullable = false)
    private int totalLessons;

    @Column(name = "total_amount_rubles", nullable = false)
    private int totalAmountRubles;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "subscription", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordinal asc")
    private List<LessonSubscriptionCredit> credits = new ArrayList<>();

    protected LessonSubscription() {}

    public LessonSubscription(User teacher, User student, int totalLessons, int totalAmountRubles, Instant now) {
        if (totalLessons < 1 || totalLessons > 100)
            throw new IllegalArgumentException("Количество занятий должно быть от 1 до 100");
        if (totalAmountRubles < totalLessons || (long) totalAmountRubles > (long) totalLessons * 1_000_000L)
            throw new IllegalArgumentException("Стоимость абонемента указана некорректно");
        this.teacher = teacher;
        this.student = student;
        this.totalLessons = totalLessons;
        this.totalAmountRubles = totalAmountRubles;
        this.createdAt = now;
        int base = totalAmountRubles / totalLessons;
        int remainder = totalAmountRubles % totalLessons;
        for (int ordinal = 1; ordinal <= totalLessons; ordinal++) {
            credits.add(new LessonSubscriptionCredit(this, ordinal, base + (ordinal <= remainder ? 1 : 0)));
        }
    }

    public Long getId() { return id; }
    public User getTeacher() { return teacher; }
    public User getStudent() { return student; }
    public int getTotalLessons() { return totalLessons; }
    public int getTotalAmountRubles() { return totalAmountRubles; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public long getVersion() { return version; }
    public List<LessonSubscriptionCredit> getCredits() { return Collections.unmodifiableList(credits); }
    public boolean isCancelled() { return cancelledAt != null; }
    public void cancelRemaining(Instant now) {
        if (cancelledAt == null) cancelledAt = now;
    }
}
