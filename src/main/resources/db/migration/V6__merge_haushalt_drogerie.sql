-- "Drogerie und Hygiene" geht in "Haushalt" auf. Haushalt behält seine ID (seine Buchungen bleiben unverändert) und heißt danach
-- "Haushalt und Drogerie". Buchungen, Belegpositionen und gruppeneigene Unterkategorien der Drogerie ziehen auf die zusammengeführte Kategorie um.

-- 1. Gruppeneigene Kategorie mit genau dem neuen Namen bekommt einen Zusatz, damit die Namen eindeutig bleiben
update kategorie_gruppe
set name = name || ' (eigene)'
where global_ref is null and lower(name) = 'haushalt und drogerie';

-- 2. Sichtbarkeit je Gruppe: sichtbar, wenn mindestens eine der beiden Kategorien sichtbar war.
--    War nur Haushalt ausgeblendet (Drogerie nicht), wird die zusammengeführte Kategorie eingeblendet.
update kategorie_gruppe h
set ausgeblendet = false
where h.global_ref = (select id from kategorie_global where name = 'Haushalt')
  and h.ausgeblendet
  and not exists (
      select 1 from kategorie_gruppe d
      where d.wohngruppe_id = h.wohngruppe_id
        and d.global_ref = (select id from kategorie_global where name = 'Drogerie und Hygiene')
        and d.ausgeblendet
  );

-- 3. Buchungen und Belegpositionen umhängen
update buchung
set kategorie_id = (select id from kategorie_global where name = 'Haushalt')
where kategorie_id = (select id from kategorie_global where name = 'Drogerie und Hygiene');

update buchung_position
set kategorie_id = (select id from kategorie_global where name = 'Haushalt')
where kategorie_id = (select id from kategorie_global where name = 'Drogerie und Hygiene');

-- 4. Unterkategorien umhängen
update kategorie_gruppe
set parent_id = (select id from kategorie_global where name = 'Haushalt')
where parent_id = (select id from kategorie_global where name = 'Drogerie und Hygiene');

update kategorie_global
set parent_id = (select id from kategorie_global where name = 'Haushalt')
where parent_id = (select id from kategorie_global where name = 'Drogerie und Hygiene');

-- 5. Einstellungen der Gruppen zur Drogerie und die Kategorie selbst entfernen, Haushalt umbenennen
delete from kategorie_gruppe
where global_ref = (select id from kategorie_global where name = 'Drogerie und Hygiene');

delete from kategorie_global where name = 'Drogerie und Hygiene';

update kategorie_global set name = 'Haushalt und Drogerie' where name = 'Haushalt';
