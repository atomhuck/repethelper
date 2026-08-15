package ru.repethelper.web.form;

import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import ru.repethelper.domain.LessonChangeScope;
import ru.repethelper.domain.LessonRecurrence;
import java.time.LocalDateTime;

public class LessonForm {
    @NotNull(message = "Выберите ученика")
    private Long studentId;
    @NotNull(message = "Укажите дату и время") @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime startAt;
    @Min(value = 15, message = "Минимальная длительность — 15 минут") @Max(value = 300, message = "Максимальная длительность — 300 минут")
    private int durationMinutes = 60;
    @Min(value = 1, message = "Минимальная стоимость — 1 ₽")
    @Max(value = 1_000_000, message = "Максимальная стоимость — 1 000 000 ₽")
    private Integer priceRubles;
    @NotNull
    private ru.repethelper.domain.LessonPaymentMode paymentMode = ru.repethelper.domain.LessonPaymentMode.SINGLE;
    @Min(value = 1, message = "В абонементе должно быть хотя бы одно занятие")
    @Max(value = 100, message = "В абонементе может быть не больше 100 занятий")
    private Integer subscriptionLessonCount;
    @Min(value = 1, message = "Укажите стоимость абонемента")
    @Max(value = 100_000_000, message = "Стоимость абонемента слишком большая")
    private Integer subscriptionTotalRubles;
    private boolean useSubscriptionForSeries = true;
    @NotNull
    private LessonRecurrence recurrence = LessonRecurrence.ONCE;
    @NotNull
    private LessonChangeScope scope = LessonChangeScope.SINGLE;
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public Integer getPriceRubles() { return priceRubles; }
    public void setPriceRubles(Integer priceRubles) { this.priceRubles = priceRubles; }
    public ru.repethelper.domain.LessonPaymentMode getPaymentMode() { return paymentMode; }
    public void setPaymentMode(ru.repethelper.domain.LessonPaymentMode paymentMode) { this.paymentMode = paymentMode; }
    public Integer getSubscriptionLessonCount() { return subscriptionLessonCount; }
    public void setSubscriptionLessonCount(Integer subscriptionLessonCount) { this.subscriptionLessonCount = subscriptionLessonCount; }
    public Integer getSubscriptionTotalRubles() { return subscriptionTotalRubles; }
    public void setSubscriptionTotalRubles(Integer subscriptionTotalRubles) { this.subscriptionTotalRubles = subscriptionTotalRubles; }
    public boolean isUseSubscriptionForSeries() { return useSubscriptionForSeries; }
    public void setUseSubscriptionForSeries(boolean useSubscriptionForSeries) { this.useSubscriptionForSeries = useSubscriptionForSeries; }
    public LessonRecurrence getRecurrence() { return recurrence; }
    public void setRecurrence(LessonRecurrence recurrence) { this.recurrence = recurrence; }
    public LessonChangeScope getScope() { return scope; }
    public void setScope(LessonChangeScope scope) { this.scope = scope; }
}
