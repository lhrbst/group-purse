-- Unterkategorien (eine Ebene) und Positionen je Ausgabe
alter table kategorie_global add column parent_id uuid references kategorie_global(id);
alter table kategorie_gruppe add column parent_id uuid;

insert into kategorie_global (id, name, parent_id)
select gen_random_uuid(), s.name, (select id from kategorie_global where name = 'Lebensmittel')
from (values
    ('Obst und Gemüse'),
    ('Brot und Backwaren'),
    ('Milch, Käse und Eier'),
    ('Fleisch, Wurst und Fisch'),
    ('Tiefkühl und Fertiggerichte'),
    ('Grundnahrungsmittel'),
    ('Getränke'),
    ('Süßes und Snacks'),
    ('Aufstriche und Frühstück'),
    ('Sonstige Lebensmittel')
) as s(name);

create table buchung_position (
    id uuid primary key,
    wohngruppe_id uuid not null references wohngruppe(id),
    buchung_id uuid not null references buchung(id),
    kategorie_id uuid not null,
    betrag numeric(12,2) not null check (betrag > 0),
    position int not null default 0
);
create index buchung_position_buchung_idx on buchung_position (buchung_id);

-- bestehende Ausgaben bekommen genau eine Position über den vollen Betrag
insert into buchung_position (id, wohngruppe_id, buchung_id, kategorie_id, betrag, position)
select gen_random_uuid(), wohngruppe_id, id, kategorie_id, -betrag, 0
from buchung
where art in ('AUSGABE', 'KONTOAUSGABE') and kategorie_id is not null and betrag < 0;
