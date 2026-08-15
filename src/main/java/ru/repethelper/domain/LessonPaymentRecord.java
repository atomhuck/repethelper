package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "lesson_payment_records")
public class LessonPaymentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "amount_rubles", nullable = false)
    private int amountRubles;

    @Column(name = "lesson_start_at", nullable = false)
    private Instant lessonStartAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_source", nullable = false, length = 20)
    private PaymentSource paymentSource = PaymentSource.MANUAL;

    protected LessonPaymentRecord() {}

    public Long getId() { return id; }
    public User getTeacher() { return teacher; }
    public Long getLessonId() { return lessonId; }
    public int getAmountRubles() { return amountRubles; }
    public Instant getLessonStartAt() { return lessonStartAt; }
    public Instant getRecordedAt() { return recordedAt; }
    public PaymentSource getPaymentSource() { return paymentSource; }
}
