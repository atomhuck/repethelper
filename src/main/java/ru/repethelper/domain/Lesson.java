package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "lessons")
public class Lesson {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "student_id")
    private User student;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "teacher_id")
    private User teacher;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "series_id")
    private LessonSeries series;
    @Column(name = "occurrence_index")
    private Integer occurrenceIndex;
    @Column(name = "start_at", nullable = false)
    private Instant startAt;
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LessonStatus status;
    @Column(name = "homework_text", columnDefinition = "text")
    private String homeworkText;
    @Enumerated(EnumType.STRING)
    @Column(name = "homework_submission_status", nullable = false, length = 20)
    private HomeworkSubmissionStatus homeworkSubmissionStatus = HomeworkSubmissionStatus.NOT_MARKED;
    @Column(name = "lesson_notes_text", columnDefinition = "text")
    private String lessonNotesText;
    @Column(name = "teacher_private_note", columnDefinition = "text")
    private String teacherPrivateNote;
    @Column(name = "meeting_url", columnDefinition = "text")
    private String meetingUrl;
    @Column(name = "price_rubles")
    private Integer priceRubles;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.NO_PRICE;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_credit_id", unique = true)
    private LessonSubscriptionCredit subscriptionCredit;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    protected Lesson() {}
    public Lesson(User teacher, User student, Instant startAt, int durationMinutes) {
        this(teacher, student, startAt, durationMinutes, null);
    }
    public Lesson(User teacher, User student, Instant startAt, int durationMinutes, Integer priceRubles) {
        this.teacher = teacher; this.student = student; this.startAt = startAt; this.durationMinutes = durationMinutes;
        this.priceRubles = priceRubles;
        this.paymentStatus = priceRubles == null ? PaymentStatus.NO_PRICE : PaymentStatus.UNPAID;
        this.status = LessonStatus.SCHEDULED; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public Lesson(LessonSeries series, int occurrenceIndex) {
        this(series.getTeacher(), series.getStudent(), series.occurrenceStart(occurrenceIndex),
                series.getDurationMinutes(), series.priceAt(occurrenceIndex));
        this.series = series;
        this.occurrenceIndex = occurrenceIndex;
    }
    public Long getId() { return id; }
    public User getStudent() { return student; }
    public User getTeacher() { return teacher; }
    public LessonSeries getSeries() { return series; }
    public Integer getOccurrenceIndex() { return occurrenceIndex; }
    public boolean isRecurring() { return series != null; }
    public Instant getStartAt() { return startAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public LessonStatus getStatus() { return status; }
    public String getHomeworkText() { return homeworkText; }
    public HomeworkSubmissionStatus getHomeworkSubmissionStatus() { return homeworkSubmissionStatus; }
    public String getLessonNotesText() { return lessonNotesText; }
    public String getTeacherPrivateNote() { return teacherPrivateNote; }
    public String getMeetingUrl() { return meetingUrl; }
    public Integer getPriceRubles() { return priceRubles; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public LessonSubscriptionCredit getSubscriptionCredit() { return subscriptionCredit; }
    public boolean isPaidBySubscription() { return subscriptionCredit != null; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getEndAt() { return startAt.plus(Duration.ofMinutes(durationMinutes)); }
    public boolean isPast(Instant now) { return status != LessonStatus.CANCELLED && !getEndAt().isAfter(now); }
    public void reschedule(Instant startAt, int durationMinutes) { this.startAt = startAt; this.durationMinutes = durationMinutes; touch(); }
    public void updateMaterials(String homeworkText, String notesText) { this.homeworkText = homeworkText; this.lessonNotesText = notesText; touch(); }
    public void updateHomeworkSubmissionStatus(HomeworkSubmissionStatus status) {
        this.homeworkSubmissionStatus = Objects.requireNonNull(status);
        touch();
    }
    public void updateTeacherPrivateNote(String teacherPrivateNote) { this.teacherPrivateNote = teacherPrivateNote; touch(); }
    public void updateMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; touch(); }
    public void updatePrice(Integer priceRubles) {
        if (subscriptionCredit != null) throw new IllegalStateException("Сначала верните занятие в абонемент");
        if (Objects.equals(this.priceRubles, priceRubles)) return;
        this.priceRubles = priceRubles;
        this.paymentStatus = priceRubles == null ? PaymentStatus.NO_PRICE : PaymentStatus.UNPAID;
        touch();
    }
    public void updatePaymentStatus(PaymentStatus paymentStatus) {
        if (subscriptionCredit != null) throw new IllegalStateException("Оплата абонементного занятия меняется через абонемент");
        Objects.requireNonNull(paymentStatus);
        if (priceRubles == null || paymentStatus == PaymentStatus.NO_PRICE)
            throw new IllegalArgumentException("Сначала укажите стоимость занятия");
        if (this.paymentStatus == paymentStatus) return;
        this.paymentStatus = paymentStatus;
        touch();
    }
    public void attachSubscriptionCredit(LessonSubscriptionCredit credit) {
        if (subscriptionCredit != null && !subscriptionCredit.getId().equals(credit.getId()))
            throw new IllegalStateException("К занятию уже применён абонемент");
        this.subscriptionCredit = Objects.requireNonNull(credit);
        this.priceRubles = credit.getAmountRubles();
        this.paymentStatus = PaymentStatus.PAID;
        touch();
    }
    public void releaseSubscriptionCredit() {
        if (subscriptionCredit == null) return;
        this.subscriptionCredit = null;
        this.paymentStatus = PaymentStatus.UNPAID;
        touch();
    }
    public void cancel() { this.status = LessonStatus.CANCELLED; touch(); }
    private void touch() { this.updatedAt = Instant.now(); }
}
