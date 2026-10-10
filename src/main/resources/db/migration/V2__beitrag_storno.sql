alter table beitrag
    add column storniert boolean not null default false,
    add column storno_begruendung text;
