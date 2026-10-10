-- Belege werden zuerst nur als Foto gespeichert und im Hintergrund von der KI gelesen
alter table buchung drop constraint buchung_status_check;
alter table buchung add constraint buchung_status_check
    check (status in ('GEBUCHT','EINGEREICHT','GEPRUEFT','ABGELEHNT','STORNIERT','VERARBEITUNG','MANUELL'));

alter table buchung
    add column verarbeitung_versuche int not null default 0,
    add column verarbeitung_fehler text,
    add column verarbeitung_gestartet_am timestamptz,
    add column verarbeitung_naechster_versuch timestamptz;

create index buchung_verarbeitung_idx on buchung (erfasst_am) where status = 'VERARBEITUNG';
