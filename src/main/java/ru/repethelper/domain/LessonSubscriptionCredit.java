package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "lesson_subscription_credits")
public class LessonSubscriptionCredit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private LessonSubscription subscription;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "amount_rubles", nullable = false)
    private int amountRubles;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumption_reason", length = 20)
    private SubscriptionCreditConsumptionReason consumptionReason;

    @Column(name = "consumed_lesson_start_at")
    private Instant consumedLessonStartAt;

    protected LessonSubscriptionCredit() {}

    LessonSubscriptionCredit(LessonSubscription subscription, int ordinal, int amountRubles) {
        this.subscription = subscription;
        this.ordinal = ordinal;
        this.amountRubles = amountRubles;
    }

    public Long getId() { return id; }
    public LessonSubscription getSubscription() { return subscription; }
    public int getOrdinal() { return ordinal; }
    public int getAmountRubles() { return amountRubles; }
    public Instant getConsumedAt() { return consumedAt; }
    public SubscriptionCreditConsumptionReason getConsumptionReason() { return consumptionReason; }
    public Instant getConsumedLessonStartAt() { return consumedLessonStartAt; }
    public boolean isConsumed() { return consumedAt != null; }
    public void consumeAsNoShow(Instant lessonStartAt, Instant now) {
        if (consumedAt != null) return;
        consumedAt = now;
        consumptionReason = SubscriptionCreditConsumptionReason.NO_SHOW;
        consumedLessonStartAt = lessonStartAt;
    }
}
