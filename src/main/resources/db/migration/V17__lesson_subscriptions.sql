create table lesson_subscriptions (
    id bigserial primary key,
    teacher_id bigint not null references app_users(id) on delete cascade,
    student_id bigint not null references app_users(id) on delete cascade,
    total_lessons integer not null check (total_lessons between 1 and 100),
    total_amount_rubles integer not null,
    created_at timestamptz not null,
    cancelled_at timestamptz,
    version bigint not null default 0,
    constraint ck_subscription_total_amount check (
        total_amount_rubles between total_lessons and total_lessons * 1000000
    )
);

create index idx_subscriptions_teacher_student_created
    on lesson_subscriptions(teacher_id, student_id, created_at, id);
create index idx_subscriptions_student_teacher
    on lesson_subscriptions(student_id, teacher_id);

create table lesson_subscription_credits (
    id bigserial primary key,
    subscription_id bigint not null references lesson_subscriptions(id) on delete cascade,
    ordinal integer not null check (ordinal between 1 and 100),
    amount_rubles integer not null check (amount_rubles between 1 and 1000000),
    consumed_at timestamptz,
    consumption_reason varchar(20),
    consumed_lesson_start_at timestamptz,
    constraint uq_subscription_credit_ordinal unique (subscription_id, ordinal),
    constraint ck_subscription_credit_consumption check (
        (consumed_at is null and consumption_reason is null and consumed_lesson_start_at is null)
        or
        (consumed_at is not null and consumption_reason = 'NO_SHOW' and consumed_lesson_start_at is not null)
    )
);

create index idx_subscription_credits_available
    on lesson_subscription_credits(subscription_id, ordinal)
    where consumed_at is null;

alter table lessons add column subscription_credit_id bigint;
alter table lessons add constraint fk_lessons_subscription_credit
    foreign key (subscription_credit_id) references lesson_subscription_credits(id);
create unique index uq_lessons_subscription_credit
    on lessons(subscription_credit_id) where subscription_credit_id is not null;

alter table lesson_series
    add column use_subscription_by_default boolean not null default false;

alter table lesson_payment_records
    add column payment_source varchar(20) not null default 'MANUAL';
alter table lesson_payment_records
    add constraint ck_lesson_payment_source check (payment_source in ('MANUAL', 'SUBSCRIPTION'));

create or replace function sync_lesson_payment_record()
returns trigger
language plpgsql
as $$
begin
    if new.payment_status = 'PAID' and new.price_rubles is not null then
        insert into lesson_payment_records(
            teacher_id, lesson_id, amount_rubles, lesson_start_at, recorded_at, payment_source
        ) values (
            new.teacher_id, new.id, new.price_rubles, new.start_at, current_timestamp,
            case when new.subscription_credit_id is null then 'MANUAL' else 'SUBSCRIPTION' end
        )
        on conflict (lesson_id) where lesson_id is not null
        do update set
            teacher_id = excluded.teacher_id,
            amount_rubles = excluded.amount_rubles,
            lesson_start_at = excluded.lesson_start_at,
            payment_source = excluded.payment_source;
    else
        delete from lesson_payment_records where lesson_id = new.id;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_lessons_sync_payment_record on lessons;
create trigger trg_lessons_sync_payment_record
after insert or update of payment_status, price_rubles, start_at, teacher_id, subscription_credit_id
on lessons
for each row execute function sync_lesson_payment_record();

create or replace function protect_subscription_lesson()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'UPDATE'
       and old.subscription_credit_id is not null
       and new.subscription_credit_id = old.subscription_credit_id
       and (new.price_rubles is distinct from old.price_rubles
            or new.payment_status is distinct from old.payment_status) then
        raise exception 'SUBSCRIPTION_LESSON_LOCKED' using errcode = '23514';
    end if;

    if tg_op = 'DELETE' and old.subscription_credit_id is not null
       and exists (
           select 1 from lesson_subscription_credits c
           where c.id = old.subscription_credit_id and c.consumed_at is null
       ) then
        delete from lesson_payment_records where lesson_id = old.id;
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

create trigger trg_lessons_protect_subscription
before update of price_rubles, payment_status, subscription_credit_id or delete
on lessons
for each row execute function protect_subscription_lesson();
