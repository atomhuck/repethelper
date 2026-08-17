create table admin_accounts (
    id bigserial primary key,
    username varchar(40) not null,
    password_hash varchar(100) not null,
    totp_secret_ciphertext varchar(512) not null,
    enabled boolean not null default true,
    auth_version bigint not null default 0,
    last_login_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create unique index uq_admin_accounts_username_lower on admin_accounts(lower(username));

create table admin_recovery_codes (
    id bigserial primary key,
    admin_id bigint not null references admin_accounts(id) on delete cascade,
    code_hash varchar(100) not null,
    used_at timestamptz,
    created_at timestamptz not null
);
create index idx_admin_recovery_codes_available on admin_recovery_codes(admin_id, id) where used_at is null;

create table admin_audit_log (
    id bigserial primary key,
    admin_id bigint references admin_accounts(id) on delete set null,
    action varchar(80) not null,
    target_type varchar(40),
    target_id bigint,
    reason varchar(500),
    request_id uuid not null,
    source_ip varchar(64),
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null
);
create index idx_admin_audit_created on admin_audit_log(created_at desc, id desc);
create index idx_admin_audit_target on admin_audit_log(target_type, target_id, created_at desc);

alter table app_users
    add column last_login_at timestamptz,
    add column last_activity_at timestamptz,
    add column deletion_scheduled_at timestamptz,
    add column deletion_completed_at timestamptz,
    add column must_change_password boolean not null default false;
create index idx_app_users_last_activity on app_users(last_activity_at desc);
create index idx_app_users_deletion_scheduled on app_users(deletion_scheduled_at) where deletion_completed_at is null;

create table user_account_restrictions (
    id bigserial primary key,
    user_id bigint not null references app_users(id),
    admin_id bigint references admin_accounts(id) on delete set null,
    public_reason varchar(500) not null,
    internal_note varchar(2000),
    starts_at timestamptz not null,
    ends_at timestamptz,
    lifted_at timestamptz,
    lifted_by_admin_id bigint references admin_accounts(id) on delete set null,
    constraint ck_restriction_dates check (ends_at is null or ends_at > starts_at)
);
create index idx_user_restrictions_active on user_account_restrictions(user_id, starts_at desc) where lifted_at is null;

create table admin_user_invitations (
    id uuid primary key,
    email varchar(254) not null,
    role varchar(20) not null check (role in ('TEACHER', 'STUDENT')),
    token_hash varchar(64) not null unique,
    created_by_admin_id bigint references admin_accounts(id) on delete set null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    revoked_at timestamptz
);
create index idx_admin_invitation_email_active on admin_user_invitations(lower(email), expires_at desc)
    where used_at is null and revoked_at is null;

create table admin_support_grants (
    id uuid primary key,
    admin_id bigint not null references admin_accounts(id) on delete cascade,
    user_id bigint not null references app_users(id),
    reason varchar(500) not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null
);
create index idx_admin_support_grant_active on admin_support_grants(admin_id, user_id, expires_at) where revoked_at is null;

create table product_activity_events (
    id bigserial primary key,
    user_id bigint references app_users(id) on delete set null,
    event_type varchar(40) not null,
    route_group varchar(40),
    occurred_at timestamptz not null
);
create index idx_product_activity_events_time on product_activity_events(occurred_at desc);
create index idx_product_activity_events_user_time on product_activity_events(user_id, occurred_at desc);

create table anonymous_visit_fingerprints (
    id bigserial primary key,
    fingerprint_hash varchar(64) not null,
    visit_day date not null,
    expires_at timestamptz not null,
    created_at timestamptz not null,
    constraint uq_anonymous_visit_daily unique(fingerprint_hash, visit_day)
);
create index idx_anonymous_visit_expiry on anonymous_visit_fingerprints(expires_at);

create table product_daily_metrics (
    metric_day date primary key,
    page_views bigint not null default 0,
    anonymous_uniques bigint not null default 0,
    registrations bigint not null default 0,
    lessons_created bigint not null default 0,
    boards_active bigint not null default 0,
    peak_online bigint not null default 0,
    updated_at timestamptz not null
);

create table product_online_samples (
    sampled_at timestamptz primary key,
    online_users integer not null check (online_users >= 0)
);
create index idx_product_online_samples_time on product_online_samples(sampled_at desc);
