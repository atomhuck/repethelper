package ru.repethelper.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.repethelper.domain.*;
import ru.repethelper.service.*;
import ru.repethelper.web.form.*;
import java.time.LocalDateTime;

@Controller
public class LessonController {
    private final AccountService accounts;
    private final LessonService lessons;
    private final AttachmentService attachments;
    private final WhiteboardService whiteboards;
    private final TextLinkifier textLinkifier;
    private final LessonSubscriptionService subscriptions;
    public LessonController(AccountService accounts, LessonService lessons, AttachmentService attachments,
                            WhiteboardService whiteboards, TextLinkifier textLinkifier,
                            LessonSubscriptionService subscriptions) {
        this.accounts = accounts; this.lessons = lessons; this.attachments = attachments; this.whiteboards = whiteboards;
        this.textLinkifier = textLinkifier;
        this.subscriptions = subscriptions;
    }
    @GetMapping("/lessons/{id}")
    String details(Authentication auth, @PathVariable Long id,
                   @RequestParam(required = false) String from, Model model) {
        User user = accounts.requireByUsername(auth.getName());
        Lesson lesson = lessons.requireAccessible(user, id);
        Whiteboard board = whiteboards.getOrCreate(user, lesson);
        var all = attachments.list(lesson);
        LessonMaterialsForm materials = new LessonMaterialsForm();
        materials.setHomeworkText(lesson.getHomeworkText()); materials.setLessonNotesText(lesson.getLessonNotesText());
        MeetingUrlForm meetingUrl = new MeetingUrlForm();
        meetingUrl.setMeetingUrl(lesson.getMeetingUrl());
        PrivateLessonNoteForm privateNote = new PrivateLessonNoteForm();
        privateNote.setNote(lesson.getTeacherPrivateNote());
        LessonForm schedule = new LessonForm();
        schedule.setStudentId(lesson.getStudent().getId()); schedule.setStartAt(LocalDateTime.ofInstant(lesson.getStartAt(), lessons.zone()));
        schedule.setDurationMinutes(lesson.getDurationMinutes());
        LessonPriceForm priceForm = new LessonPriceForm();
        priceForm.setPriceRubles(lesson.getPriceRubles());
        model.addAttribute("user", user);
        model.addAttribute("lesson", lesson);
        model.addAttribute("board", board);
        model.addAttribute("past", lessons.isPast(lesson));
        model.addAttribute("started", lessons.hasStarted(lesson));
        model.addAttribute("materialsForm", materials);
        model.addAttribute("meetingUrlForm", meetingUrl);
        if (user.getRole() == Role.TEACHER) model.addAttribute("privateNoteForm", privateNote);
        if (user.getRole() == Role.TEACHER) model.addAttribute("priceForm", priceForm);
        model.addAttribute("fromStudentCard", user.getRole() == Role.TEACHER && "student".equals(from));
        model.addAttribute("homeworkHtml", lesson.getHomeworkText() == null ? null : textLinkifier.linkify(lesson.getHomeworkText()));
        model.addAttribute("lessonNotesHtml", lesson.getLessonNotesText() == null ? null : textLinkifier.linkify(lesson.getLessonNotesText()));
        model.addAttribute("lessonForm", schedule);
        model.addAttribute("homeworkFiles", all.stream().filter(a -> a.getCategory() == AttachmentCategory.HOMEWORK).toList());
        model.addAttribute("notesFiles", all.stream().filter(a -> a.getCategory() == AttachmentCategory.LESSON_NOTES).toList());
        model.addAttribute("subscriptionEnabled", subscriptions.isEnabled());
        if (user.getRole() == Role.TEACHER && subscriptions.isEnabled())
            model.addAttribute("subscriptionSummary", subscriptions.summary(user, lesson.getStudent()));
        return "lesson";
    }
}
