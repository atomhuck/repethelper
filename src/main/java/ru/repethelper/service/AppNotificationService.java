package ru.repethelper.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.repethelper.domain.*;
import ru.repethelper.repository.EmailNotificationRepository;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class AppNotificationService {
    private static final Logger log = LoggerFactory.getLogger(AppNotificationService.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"));
    private final EmailNotificationRepository notifications;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ZoneId zone;
    private final String baseUrl;

    public AppNotificationService(EmailNotificationRepository notifications, ObjectMapper objectMapper, Clock clock,
                                  @Value("${app.timezone}") String timezone,
                                  @Value("${app.base-url}") String baseUrl) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.zone = ZoneId.of(timezone);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public void connectionRequested(ConnectionRequest request) {
        User teacher = request.getTeacher();
        enqueueVerified(EmailNotificationType.CONNECTION_REQUEST_RECEIVED, teacher,
                request.getStudent().getId(), teacher.getId(), null, null,
                "Новая заявка ученика в RepetHelper",
                "Ученик " + request.getStudent().getDisplayName()
                        + " отправил вам заявку.\n\nОткрыть заявки: " + baseUrl + "/teacher/students",
                "CONNECTION_REQUEST:" + request.getId(), clock.instant());
    }

    @Transactional
    public void connectionProcessed(ConnectionRequest request) {
        boolean accepted = request.getStatus() == ConnectionStatus.ACCEPTED;
        enqueueVerified(accepted ? EmailNotificationType.CONNECTION_ACCEPTED
                        : EmailNotificationType.CONNECTION_REJECTED,
                request.getStudent(), request.getStudent().getId(), request.getTeacher().getId(), null, null,
                accepted ? "Заявка принята в RepetHelper" : "Заявка отклонена в RepetHelper",
                accepted
                        ? "Преподаватель " + request.getTeacher().getDisplayName()
                            + " принял вашу заявку.\n\nОткрыть кабинет: " + baseUrl + "/student"
                        : "Преподаватель " + request.getTeacher().getDisplayName()
                            + " отклонил вашу заявку.\n\nВы можете отправить новую заявку позднее: "
                            + baseUrl + "/student",
                "CONNECTION_PROCESSED:" + request.getId() + ":" + request.getStatus(), clock.instant());
    }

    @Transactional
    public void lessonCreated(Lesson lesson) {
        enqueueVerified(EmailNotificationType.LESSON_CREATED, lesson.getStudent(),
                lesson.getStudent().getId(), lesson.getTeacher().getId(), lesson.getId(), null,
                "Добавлено занятие в RepetHelper",
                lessonDescription("Преподаватель добавил занятие.", lesson, true),
                "LESSON_CREATED:" + lesson.getId(), clock.instant());
    }

    @Transactional
    public void seriesCreated(Lesson lesson) {
        enqueueVerified(EmailNotificationType.LESSON_SERIES_CREATED, lesson.getStudent(),
                lesson.getStudent().getId(), lesson.getTeacher().getId(), lesson.getId(),
                lesson.getSeries().getId().toString(),
                "Добавлены еженедельные занятия в RepetHelper",
                lessonDescription("Преподаватель добавил еженедельные занятия. Ближайшая встреча:", lesson, true),
                "SERIES_CREATED:" + lesson.getSeries().getId(), clock.instant());
    }

    @Transactional
    public void lessonRescheduled(Lesson lesson, Instant oldStart, int oldDuration, boolean following) {
        cancelReminder(lesson.getId());
        EmailNotificationType type = following ? EmailNotificationType.LESSON_SERIES_RESCHEDULED
                : EmailNotificationType.LESSON_RESCHEDULED;
        String subject = following ? "Перенесены еженедельные занятия в RepetHelper"
                : "Занятие перенесено в RepetHelper";
        String body = "Преподаватель " + lesson.getTeacher().getDisplayName() + " перенёс "
                + (following ? "это и последующие занятия." : "занятие.")
                + "\n\nБыло: " + format(oldStart) + ", " + oldDuration + " минут"
                + "\nСтало: " + format(lesson.getStartAt()) + ", " + lesson.getDurationMinutes() + " минут"
                + "\n\nОткрыть занятие: " + lessonUrl(lesson);
        enqueueVerified(type, lesson.getStudent(), lesson.getStudent().getId(), lesson.getTeacher().getId(),
                lesson.getId(), following ? lesson.getSeries().getId().toString() : null,
                subject, body,
                "RESCHEDULE:" + lesson.getId() + ":" + oldStart.toEpochMilli() + ":"
                        + lesson.getStartAt().toEpochMilli() + ":" + following,
                clock.instant());
    }

    @Transactional
    public void lessonDeleted(Lesson lesson, boolean following) {
        cancelReminder(lesson.getId());
        EmailNotificationType type = following ? EmailNotificationType.LESSON_SERIES_DELETED
                : EmailNotificationType.LESSON_DELETED;
        enqueueVerified(type, lesson.getStudent(), lesson.getStudent().getId(), lesson.getTeacher().getId(),
                lesson.getId(), following && lesson.getSeries() != null ? lesson.getSeries().getId().toString() : null,
                following ? "Удалены последующие занятия в RepetHelper" : "Занятие удалено в RepetHelper",
                "Преподаватель " + lesson.getTeacher().getDisplayName() + " удалил "
                        + (following ? "занятие " + format(lesson.getStartAt()) + " и все последующие встречи серии."
                        : "занятие, назначенное на " + format(lesson.getStartAt()) + "."),
                (following ? "SERIES_DELETED:" + lesson.getSeries().getId() + ":" + lesson.getOccurrenceIndex()
                        : "LESSON_DELETED:" + lesson.getId()),
                clock.instant());
    }

    @Transactional
    public void homeworkUpdated(Lesson lesson) {
        long bucket = clock.instant().getEpochSecond() / 120;
        enqueueVerified(EmailNotificationType.HOMEWORK_UPDATED, lesson.getStudent(),
                lesson.getStudent().getId(), lesson.getTeacher().getId(), lesson.getId(), null,
                "Обновлено домашнее задание в RepetHelper",
                lessonDescription("Преподаватель обновил домашнее задание.", lesson, true),
                "HOMEWORK:" + lesson.getId() + ":" + bucket, clock.instant().plusSeconds(120));
    }

    @Transactional
    public void reminder(Lesson lesson) {
        String call = lesson.getMeetingUrl() == null ? ""
                : "\nСсылка на звонок: " + lesson.getMeetingUrl();
        enqueueVerified(EmailNotificationType.LESSON_REMINDER, lesson.getStudent(),
                lesson.getStudent().getId(), lesson.getTeacher().getId(), lesson.getId(), null,
                "Напоминание о предстоящем занятии",
                lessonDescription("Напоминаем о предстоящем занятии.", lesson, true) + call,
                "REMINDER:" + lesson.getId() + ":" + lesson.getStartAt().toEpochMilli(), clock.instant());
    }

    @Transactional
    public void subscriptionCreated(LessonSubscription subscription) {
        enqueueVerified(EmailNotificationType.SUBSCRIPTION_CREATED, subscription.getStudent(),
                subscription.getStudent().getId(), subscription.getTeacher().getId(), null, null,
                "Преподаватель добавил абонемент в RepetHelper",
                "Преподаватель " + subscription.getTeacher().getDisplayName()
                        + " добавил вам абонемент на " + subscription.getTotalLessons() + " занятий."
                        + "\n\nОткрыть кабинет: " + baseUrl + "/student",
                "SUBSCRIPTION_CREATED:" + subscription.getId(), clock.instant());
    }

    @Transactional
    public void cancelReminder(Long lessonId) {
        if (lessonId != null) notifications.cancelPendingReminder(lessonId);
    }

    @Transactional
    public void cancelTransientForDeletedLesson(Long lessonId) {
        if (lessonId != null) notifications.cancelTransientForDeletedLesson(lessonId);
    }

    private void enqueueVerified(EmailNotificationType type, User recipient, Long studentId, Long teacherId,
                                 Long lessonId, String seriesId, String subject, String body,
                                 String dedupeKey, Instant availableAt) {
        if (recipient == null || !recipient.isEmailVerified()) {
            log.info("Email-уведомление {} пропущено: у пользователя {} нет подтверждённого email",
                    type, recipient == null ? "unknown" : recipient.getId());
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("subject", subject);
        payload.put("body", body);
        try {
            Instant now = clock.instant();
            notifications.enqueue(type.name(), recipient.getEmail(), studentId, teacherId, lessonId, seriesId,
                    objectMapper.writeValueAsString(payload), dedupeKey, availableAt, now);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Не удалось подготовить email-уведомление", ex);
        }
    }

    private String lessonDescription(String intro, Lesson lesson, boolean includeLink) {
        return intro
                + "\n\nПреподаватель: " + lesson.getTeacher().getDisplayName()
                + "\nДата и время: " + format(lesson.getStartAt())
                + "\nДлительность: " + lesson.getDurationMinutes() + " минут"
                + (includeLink ? "\n\nОткрыть занятие: " + lessonUrl(lesson) : "");
    }

    private String lessonUrl(Lesson lesson) { return baseUrl + "/lessons/" + lesson.getId(); }
    private String format(Instant instant) { return DATE_TIME.format(instant.atZone(zone)); }
}
