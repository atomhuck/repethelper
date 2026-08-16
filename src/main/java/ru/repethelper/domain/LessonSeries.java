package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "lesson_series")
public class LessonSeries {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private User student;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private User teacher;

    @Column(name = "anchor_start_at", nullable = false)
    private Instant anchorStartAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "base_price_rubles")
    private Integer basePriceRubles;
    @Column(name = "use_subscription_by_default", nullable = false)
    private boolean useSubscriptionByDefault;

    @Column(name = "cancelled_from_index")
    private Integer cancelledFromIndex;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "lesson_series_exclusions", joinColumns = @JoinColumn(name = "series_id"))
    @Column(name = "occurrence_index", nullable = false)
    private Set<Integer> excludedOccurrenceIndexes = new HashSet<>();

    @OneToMany(mappedBy = "series", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LessonSeriesPriceChange> priceChanges = new ArrayList<>();

    protected LessonSeries() {}

    public LessonSeries(User teacher, User student, Instant anchorStartAt, int durationMinutes) {
        this(teacher, student, anchorStartAt, durationMinutes, null);
    }

    public LessonSeries(User teacher, User student, Instant anchorStartAt, int durationMinutes, Integer basePriceRubles) {
        this.teacher = teacher;
        this.student = student;
        this.anchorStartAt = anchorStartAt;
        this.durationMinutes = durationMinutes;
        this.basePriceRubles = basePriceRubles;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public User getStudent() { return student; }
    public User getTeacher() { return teacher; }
    public Instant getAnchorStartAt() { return anchorStartAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public Integer getBasePriceRubles() { return basePriceRubles; }
    public boolean isUseSubscriptionByDefault() { return useSubscriptionByDefault; }
    public void setUseSubscriptionByDefault(boolean value) { this.useSubscriptionByDefault = value; this.updatedAt = Instant.now(); }
    public Integer getCancelledFromIndex() { return cancelledFromIndex; }
    public Instant occurrenceStart(int index) { return anchorStartAt.plus(Duration.ofDays(7L * index)); }
    public boolean includes(int index) {
        return (cancelledFromIndex == null || index < cancelledFromIndex)
                && !excludedOccurrenceIndexes.contains(index);
    }

    public Integer priceAt(int occurrenceIndex) {
        LessonSeriesPriceChange effective = priceChanges.stream()
                .filter(change -> change.getEffectiveOccurrenceIndex() <= occurrenceIndex)
                .max(Comparator.comparingInt(LessonSeriesPriceChange::getEffectiveOccurrenceIndex))
                .orElse(null);
        return effective == null ? basePriceRubles : effective.getPriceRubles();
    }

    public void changePriceFrom(int occurrenceIndex, Integer priceRubles) {
        priceChanges.removeIf(change -> change.getEffectiveOccurrenceIndex() > occurrenceIndex);
        LessonSeriesPriceChange current = priceChanges.stream()
                .filter(change -> change.getEffectiveOccurrenceIndex() == occurrenceIndex)
                .findFirst()
                .orElse(null);
        if (current == null) priceChanges.add(new LessonSeriesPriceChange(this, occurrenceIndex, priceRubles));
        else current.updatePrice(priceRubles);
        updatedAt = Instant.now();
    }

    public void shiftFrom(Instant oldOccurrenceStart, Instant newOccurrenceStart, int newDurationMinutes) {
        anchorStartAt = anchorStartAt.plus(Duration.between(oldOccurrenceStart, newOccurrenceStart));
        durationMinutes = newDurationMinutes;
        updatedAt = Instant.now();
    }

    public void cancelFrom(int index) {
        if (cancelledFromIndex == null || index < cancelledFromIndex) cancelledFromIndex = index;
        updatedAt = Instant.now();
    }
    /** Makes a newly created series finite. Existing series deliberately keep a null boundary. */
    public void limitToOccurrences(int count) {
        if (count < 1) throw new IllegalArgumentException("Количество занятий должно быть не меньше одного");
        cancelledFromIndex = count;
        updatedAt = Instant.now();
    }

    public void exclude(int index) {
        excludedOccurrenceIndexes.add(index);
        updatedAt = Instant.now();
    }
}
