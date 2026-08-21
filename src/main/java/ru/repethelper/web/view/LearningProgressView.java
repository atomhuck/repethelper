package ru.repethelper.web.view;

import java.util.List;

/** A read-only projection derived from lessons; it never creates gamification data. */
public record LearningProgressView(long completedLessons, long submittedHomework, long boardCount,
                                   List<Milestone> milestones) {
    public record Milestone(String label, boolean reached) {}
}
