package ru.repethelper.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.repethelper.domain.PaymentStatus;
import ru.repethelper.domain.PaymentSource;
import ru.repethelper.domain.Role;
import ru.repethelper.domain.User;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class FinanceService {
    public static final int PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final YearMonth EARLIEST_MONTH = YearMonth.of(2020, 1);

    private final JdbcClient jdbc;
    private final LessonService lessons;
    private final Clock clock;
    private final ZoneId zone;

    public FinanceService(JdbcClient jdbc, LessonService lessons, Clock clock,
                          @org.springframework.beans.factory.annotation.Value("${app.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.lessons = lessons;
        this.clock = clock;
        this.zone = ZoneId.of(timezone);
    }

    @Transactional
    public FinanceOverview overview(User teacher, YearMonth selectedMonth, int debtPage,
                                    Long studentId, DebtPeriod period) {
        requireTeacher(teacher);
        YearMonth current = currentMonth();
        YearMonth selected = normalizeMonth(selectedMonth);
        materializeCurrentMonth(teacher, current);
        YearMonth chartEnd = selected.isBefore(current.minusMonths(11)) ? selected : current;
        List<MonthSummary> months = monthSummaries(teacher, chartEnd, 12);
        MonthSummary selectedSummary = months.stream().filter(item -> item.month().equals(selected)).findFirst()
                .orElseGet(() -> monthSummary(teacher, selected));
        return new FinanceOverview(selected, current, selectedSummary, months,
                monthLessons(teacher, selected, 0, PAGE_SIZE),
                debts(teacher, Math.max(0, debtPage), PAGE_SIZE, studentId, period));
    }

    @Transactional
    public List<MonthSummary> monthSummaries(User teacher, YearMonth end, int count) {
        requireTeacher(teacher);
        YearMonth safeEnd = normalizeMonth(end);
        if (safeEnd.equals(currentMonth())) materializeCurrentMonth(teacher, safeEnd);
        int safeCount = Math.max(1, Math.min(24, count));
        YearMonth startMonth = safeEnd.minusMonths(safeCount - 1L);
        if (startMonth.isBefore(EARLIEST_MONTH)) startMonth = EARLIEST_MONTH;
        Instant from = startMonth.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = safeEnd.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();

        Map<YearMonth, long[]> values = new HashMap<>();
        String sql = """
                select month_start, sum(received) as received, sum(remaining) as remaining
                from (
                    select date_trunc('month', lesson_start_at at time zone :zone)::date as month_start,
                           sum(amount_rubles)::bigint as received, 0::bigint as remaining
                    from lesson_payment_records
                    where teacher_id = :teacherId and lesson_start_at >= :from and lesson_start_at < :to
                    group by month_start
                    union all
                    select date_trunc('month', start_at at time zone :zone)::date as month_start,
                           0::bigint as received, sum(price_rubles)::bigint as remaining
                    from lessons
                    where teacher_id = :teacherId and payment_status = 'UNPAID' and price_rubles is not null
                      and status <> 'CANCELLED' and start_at >= :from and start_at < :to
                    group by month_start
                ) totals
                group by month_start
                order by month_start
                """;
        jdbc.sql(sql)
                .param("zone", zone.getId())
                .param("teacherId", teacher.getId())
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(rs -> {
                    while (rs.next()) {
                        YearMonth month = YearMonth.from(rs.getDate("month_start").toLocalDate());
                        values.put(month, new long[]{rs.getLong("received"), rs.getLong("remaining")});
                    }
                    return values;
                });

        List<MonthSummary> result = new ArrayList<>();
        for (YearMonth month = startMonth; !month.isAfter(safeEnd); month = month.plusMonths(1)) {
            long[] totals = values.getOrDefault(month, new long[2]);
            result.add(new MonthSummary(month, totals[0], totals[1]));
        }
        return result;
    }

    @Transactional
    public MonthSummary monthSummary(User teacher, YearMonth month) {
        return monthSummaries(teacher, normalizeMonth(month), 1).getFirst();
    }

    @Transactional
    public PageResult<FinanceLessonRow> monthLessons(User teacher, YearMonth month, int page, int size) {
        requireTeacher(teacher);
        YearMonth selected = normalizeMonth(month);
        if (selected.equals(currentMonth())) materializeCurrentMonth(teacher, selected);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        Instant from = selected.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = selected.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        String body = """
                from (
                    select ('L' || l.id)::varchar as row_key, l.id as lesson_id, u.id as student_id,
                           u.display_name as student_name, l.start_at, l.duration_minutes,
                           l.price_rubles as amount_rubles, l.payment_status, false as deleted,
                           p.id as payment_record_id, l.status as lesson_status,
                           case when l.subscription_credit_id is null then 'MANUAL' else 'SUBSCRIPTION' end as payment_source
                    from lessons l
                    join app_users u on u.id = l.student_id
                    left join lesson_payment_records p on p.lesson_id = l.id
                    where l.teacher_id = :teacherId and l.price_rubles is not null
                      and (l.status <> 'CANCELLED' or l.payment_status = 'PAID')
                      and l.start_at >= :from and l.start_at < :to
                    union all
                    select ('P' || p.id)::varchar, null::bigint, null::bigint, null::varchar,
                           p.lesson_start_at, 0, p.amount_rubles, 'PAID'::varchar, true, p.id, 'DELETED'::varchar,
                           p.payment_source
                    from lesson_payment_records p
                    where p.teacher_id = :teacherId and p.lesson_id is null
                      and p.lesson_start_at >= :from and p.lesson_start_at < :to
                ) finance_rows
                """;
        Map<String, Object> params = Map.of(
                "teacherId", teacher.getId(), "from", Timestamp.from(from), "to", Timestamp.from(to));
        long total = jdbc.sql("select count(*) " + body).params(params).query(Long.class).single();
        List<FinanceLessonRow> rows = jdbc.sql("select * " + body + " order by start_at asc, row_key asc limit :limit offset :offset")
                .params(params).param("limit", safeSize).param("offset", (long) safePage * safeSize)
                .query((rs, rowNum) -> {
                    Instant start = rs.getTimestamp("start_at").toInstant();
                    int duration = rs.getInt("duration_minutes");
                    boolean deleted = rs.getBoolean("deleted");
                    return new FinanceLessonRow(rs.getString("row_key"), nullableLong(rs, "lesson_id"),
                            nullableLong(rs, "student_id"), rs.getString("student_name"), start, duration,
                            rs.getInt("amount_rubles"), PaymentStatus.valueOf(rs.getString("payment_status")),
                            deleted, "CANCELLED".equals(rs.getString("lesson_status")),
                            nullableLong(rs, "payment_record_id"),
                            !deleted && !start.plus(duration, ChronoUnit.MINUTES).isAfter(clock.instant()),
                            PaymentSource.valueOf(rs.getString("payment_source")),
                            "SUBSCRIPTION".equals(rs.getString("payment_source")));
                }).list();
        return new PageResult<>(rows, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public DebtPage debts(User teacher, int page, int size, Long studentId, DebtPeriod period) {
        requireTeacher(teacher);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        DebtPeriod safePeriod = period == null ? DebtPeriod.ALL : period;
        Instant now = clock.instant();
        Instant from = debtFrom(safePeriod);
        StringBuilder filters = new StringBuilder("""
                from lessons l join app_users u on u.id = l.student_id
                where l.teacher_id = :teacherId and l.payment_status = 'UNPAID'
                  and l.price_rubles is not null and l.status <> 'CANCELLED'
                  and l.start_at + l.duration_minutes * interval '1 minute' <= :now
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("teacherId", teacher.getId());
        params.put("now", Timestamp.from(now));
        if (from != null) {
            filters.append(" and l.start_at >= :fromAt\n");
            params.put("fromAt", Timestamp.from(from));
        }
        if (studentId != null) {
            filters.append(" and l.student_id = :studentId\n");
            params.put("studentId", studentId);
        }
        long[] totals = jdbc.sql("select count(*) as total_count, coalesce(sum(l.price_rubles), 0)::bigint as total_amount " + filters)
                .params(params)
                .query((rs, rowNum) -> new long[]{rs.getLong("total_count"), rs.getLong("total_amount")})
                .single();
        List<DebtRow> rows = jdbc.sql("select l.id, l.student_id, u.display_name, l.start_at, l.duration_minutes, l.price_rubles "
                        + filters + " order by l.start_at asc, l.id asc limit :limit offset :offset")
                .params(params).param("limit", safeSize).param("offset", (long) safePage * safeSize)
                .query((rs, rowNum) -> {
                    Instant start = rs.getTimestamp("start_at").toInstant();
                    Instant end = start.plus(rs.getInt("duration_minutes"), ChronoUnit.MINUTES);
                    long overdueDays = Math.max(0, Duration.between(end, now).toDays());
                    return new DebtRow(rs.getLong("id"), rs.getLong("student_id"), rs.getString("display_name"),
                            start, rs.getInt("duration_minutes"), rs.getInt("price_rubles"), overdueDays);
                }).list();
        return new DebtPage(rows, safePage, safeSize, totals[0], totals[1], studentId, safePeriod);
    }

    public YearMonth currentMonth() { return YearMonth.now(clock.withZone(zone)); }
    public ZoneId zone() { return zone; }

    private void materializeCurrentMonth(User teacher, YearMonth current) {
        Instant from = current.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = current.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusNanos(1);
        lessons.materializeForTeacher(teacher, from, to);
    }

    private Instant debtFrom(DebtPeriod period) {
        if (period == DebtPeriod.ALL) return null;
        int months = switch (period) {
            case CURRENT_MONTH -> 1;
            case MONTHS_3 -> 3;
            case MONTHS_6 -> 6;
            case MONTHS_12 -> 12;
            case ALL -> throw new IllegalStateException();
        };
        return currentMonth().minusMonths(months - 1L).atDay(1).atStartOfDay(zone).toInstant();
    }

    private YearMonth normalizeMonth(YearMonth month) {
        YearMonth current = currentMonth();
        if (month == null || month.isAfter(current)) return current;
        return month.isBefore(EARLIEST_MONTH) ? EARLIEST_MONTH : month;
    }

    private void requireTeacher(User teacher) {
        if (teacher == null || teacher.getRole() != Role.TEACHER)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record MonthSummary(YearMonth month, long received, long remaining) {
        public long expected() { return Math.addExact(received, remaining); }
    }

    public record FinanceLessonRow(String rowKey, Long lessonId, Long studentId, String studentName,
                                   Instant startAt, int durationMinutes, int amountRubles,
                                   PaymentStatus paymentStatus, boolean deleted, boolean cancelled, Long paymentRecordId,
                                   boolean completed, PaymentSource paymentSource, boolean paidBySubscription) {}

    public record DebtRow(Long lessonId, Long studentId, String studentName, Instant startAt,
                          int durationMinutes, int amountRubles, long overdueDays) {}

    public record PageResult<T>(List<T> content, int page, int size, long totalElements) {
        public int totalPages() { return totalElements == 0 ? 0 : (int) Math.ceilDiv(totalElements, size); }
        public boolean hasPrevious() { return page > 0; }
        public boolean hasNext() { return page + 1 < totalPages(); }
    }

    public record DebtPage(List<DebtRow> content, int page, int size, long totalElements, long totalAmount,
                           Long studentId, DebtPeriod period) {
        public int totalPages() { return totalElements == 0 ? 0 : (int) Math.ceilDiv(totalElements, size); }
        public boolean hasPrevious() { return page > 0; }
        public boolean hasNext() { return page + 1 < totalPages(); }
    }

    public record FinanceOverview(YearMonth selectedMonth, YearMonth currentMonth, MonthSummary selectedSummary,
                                  List<MonthSummary> chartMonths, PageResult<FinanceLessonRow> monthLessons,
                                  DebtPage debts) {}
}
