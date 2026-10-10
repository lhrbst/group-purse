create table wohngruppe (
    id uuid primary key,
    name text not null,
    beitrittscode text not null unique,
    mindestbetrag_kasse numeric(12,2) not null default 50.00,
    warnung_aktiv boolean not null default false,
    letzte_bestandserfassung timestamptz,
    created_at timestamptz not null default now()
);

create table person (
    id uuid primary key,
    name text not null,
    email text not null unique check (email = lower(email)),
    passwort_hash text not null,
    ist_admin boolean not null default false,
    datenschutz_zugestimmt_am timestamptz,
    created_at timestamptz not null default now()
);

create table mitgliedschaft (
    id uuid primary key,
    person_id uuid not null references person(id),
    wohngruppe_id uuid not null references wohngruppe(id),
    rolle text not null check (rolle in ('KASSENVERWALTER','PFLEGEKRAFT')),
    status text not null check (status in ('WARTEND','AKTIV','GESPERRT')),
    freigeschaltet_von uuid references person(id),
    freigeschaltet_am timestamptz,
    created_at timestamptz not null default now(),
    unique (person_id, wohngruppe_id)
);

create table einladung (
    id uuid primary key,
    token text not null unique,
    email text not null,
    wohngruppe_id uuid not null references wohngruppe(id),
    rolle text not null check (rolle in ('KASSENVERWALTER','PFLEGEKRAFT')),
    gueltig_bis timestamptz not null,
    eingeloest_am timestamptz,
    created_at timestamptz not null default now()
);

create table passwort_reset_token (
    id uuid primary key,
    token_hash text not null unique,
    person_id uuid not null references person(id),
    gueltig_bis timestamptz not null,
    eingeloest_am timestamptz
);

create table kategorie_global (
    id uuid primary key,
    name text not null unique
);

insert into kategorie_global (id, name) values
    (gen_random_uuid(), 'Lebensmittel'),
    (gen_random_uuid(), 'Drogerie und Hygiene'),
    (gen_random_uuid(), 'Freizeit und Ausflüge'),
    (gen_random_uuid(), 'Fahrten'),
    (gen_random_uuid(), 'Haushalt'),
    (gen_random_uuid(), 'Anschaffungen'),
    (gen_random_uuid(), 'Geschenke und Feiern'),
    (gen_random_uuid(), 'Sonstiges');

create table kategorie_gruppe (
    id uuid primary key,
    wohngruppe_id uuid not null references wohngruppe(id),
    name text,
    global_ref uuid references kategorie_global(id),
    ausgeblendet boolean not null default false,
    check ((global_ref is null and name is not null) or (global_ref is not null))
);
create unique index kategorie_gruppe_global_uq on kategorie_gruppe (wohngruppe_id, global_ref) where global_ref is not null;

create table familie (
    id uuid primary key,
    wohngruppe_id uuid not null references wohngruppe(id),
    name text not null,
    bewohner_kuerzel text,
    kontakt_email text,
    faelligkeitstag int not null default 1 check (faelligkeitstag between 1 and 28),
    aktiv boolean not null default true,
    created_at timestamptz not null default now()
);

create table sollbetrag (
    id uuid primary key,
    wohngruppe_id uuid not null references wohngruppe(id),
    familie_id uuid not null references familie(id),
    betrag numeric(12,2) not null check (betrag >= 0),
    gueltig_ab date not null
);
create index sollbetrag_familie_idx on sollbetrag (familie_id, gueltig_ab);

create table beitrag (
    id uuid primary key,
    wohngruppe_id uuid not null references wohngruppe(id),
    familie_id uuid not null references familie(id),
    betrag numeric(12,2) not null check (betrag > 0),
    eingangsdatum date not null,
    monate text[] not null,
    notiz text,
    gebucht_von uuid not null references person(id),
    gebucht_am timestamptz not null default now()
);
create index beitrag_familie_idx on beitrag (familie_id, eingangsdatum);

create table buchung (
    id uuid primary key,
    wohngruppe_id uuid not null references wohngruppe(id),
    nummer bigint not null,
    konto text not null check (konto in ('BARKASSE','VEREINSKONTO')),
    art text not null check (art in ('ANFANGSBESTAND','AUFFUELLUNG','AUSGABE','RUECKGABE','KORREKTUR','KONTOAUSGABE','STORNO')),
    betrag numeric(12,2) not null,
    datum date not null,
    kategorie_id uuid,
    zweck text,
    haendler text,
    bewohner_kuerzel text,
    status text not null check (status in ('GEBUCHT','EINGEREICHT','GEPRUEFT','ABGELEHNT','STORNIERT')),
    beleg_fotos text[] not null default '{}',
    begruendung_ohne_beleg text,
    ablehnungs_kommentar text,
    storno_von uuid references buchung(id),
    gruppe_id uuid,
    erfasst_von uuid not null references person(id),
    erfasst_am timestamptz not null default now(),
    geprueft_von uuid references person(id),
    geprueft_am timestamptz,
    unique (wohngruppe_id, nummer)
);
create index buchung_konto_idx on buchung (wohngruppe_id, konto);
create index buchung_status_idx on buchung (wohngruppe_id, status);

create table audit_log (
    id uuid primary key,
    wohngruppe_id uuid not null references wohngruppe(id),
    wer uuid,
    wann timestamptz not null default now(),
    was text not null,
    entitaet text,
    entitaet_id uuid,
    alt text,
    neu text
);

create table push_subscription (
    id uuid primary key,
    wohngruppe_id uuid not null references wohngruppe(id),
    person_id uuid not null references person(id),
    endpoint text not null,
    p256dh text not null,
    auth text not null,
    unique (person_id, endpoint)
);
