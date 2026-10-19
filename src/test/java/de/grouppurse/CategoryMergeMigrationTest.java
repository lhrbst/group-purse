package de.grouppurse;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 fasst "Drogerie und Hygiene" und "Haushalt" zusammen. Die Migration läuft hier gegen eine eigene Datenbank, die zuerst auf
 * Stand V5 gebracht und mit Daten gefüllt wird, damit nicht nur das Schema, sondern die Umhängung der Altdaten geprüft ist.
 */
class CategoryMergeMigrationTest extends AbstractIntegrationTest {
    static final String DB = "merge_test";
    static final String NEW_NAME = "Haushalt und Drogerie";

    JdbcTemplate admin;
    JdbcTemplate db;
    String url;

    UUID haushalt;
    UUID drogerie;
    UUID obst;
    UUID person;
    long nr;

    @BeforeEach
    void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("drop database if exists " + DB + " with (force)");
        admin.execute("create database " + DB);
        url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + DB);
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("5").load().migrate();
        db = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        haushalt = categoryId("Haushalt");
        drogerie = categoryId("Drogerie und Hygiene");
        obst = categoryId("Obst und Gemüse");
        person = UUID.randomUUID();
        db.update("insert into person (id, name, email, passwort_hash) values (?, 'p', 'p@example.org', 'x')", person);
    }

    @AfterEach
    void tearDown() {
        admin.execute("drop database if exists " + DB + " with (force)");
    }

    UUID categoryId(String name) {
        return db.queryForObject("select id from kategorie_global where name = ?", UUID.class, name);
    }

    UUID group(String name) {
        UUID id = UUID.randomUUID();
        db.update("insert into wohngruppe (id, name, beitrittscode) values (?, ?, ?)", id, name, name + "-" + id.toString().substring(0, 6));
        return id;
    }

    /** Eine Ausgabe mit einer Position (und, wie bei Altdaten, auch kategorie_id an der Buchung). */
    void expense(UUID group, UUID category, String amount) {
        UUID id = UUID.randomUUID();
        db.update("""
                insert into buchung (id, wohngruppe_id, nummer, konto, art, betrag, datum, kategorie_id, status, erfasst_von)
                values (?, ?, ?, 'BARKASSE', 'AUSGABE', -?::numeric, date '2026-10-01', ?, 'GEBUCHT', ?)
                """, id, group, ++nr, amount, category, person);
        db.update("insert into buchung_position (id, wohngruppe_id, buchung_id, kategorie_id, betrag, position) values (?, ?, ?, ?, ?::numeric, 0)",
                UUID.randomUUID(), group, id, category, amount);
    }

    void hide(UUID group, UUID category) {
        db.update("insert into kategorie_gruppe (id, wohngruppe_id, global_ref, ausgeblendet) values (?, ?, ?, true)", UUID.randomUUID(), group, category);
    }

    UUID ownCategory(UUID group, String name, UUID parent) {
        UUID id = UUID.randomUUID();
        db.update("insert into kategorie_gruppe (id, wohngruppe_id, name, parent_id) values (?, ?, ?, ?)", id, group, name, parent);
        return id;
    }

    boolean hiddenMerged(UUID group) {
        return Boolean.TRUE.equals(db.queryForObject(
                "select exists (select 1 from kategorie_gruppe where wohngruppe_id = ? and global_ref = ? and ausgeblendet)", Boolean.class, group, haushalt));
    }

    /** Der zweite Schritt: alles nach V5, also V6. */
    void migrate() {
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
    }

    @Test
    void theTwoCategoriesBecomeOneWithTheOldHaushaltId() {
        migrate();
        assertThat(db.queryForObject("select count(*) from kategorie_global", Integer.class)).isEqualTo(17);
        assertThat(db.queryForObject("select count(*) from kategorie_global where name = 'Drogerie und Hygiene'", Integer.class)).isZero();
        assertThat(db.queryForObject("select name from kategorie_global where id = ?", String.class, haushalt)).isEqualTo(NEW_NAME);
        assertThat(db.queryForObject("select count(*) from kategorie_global where name = 'Haushalt'", Integer.class)).isZero();
    }

    @Test
    void historyOfBothCategoriesLandsOnTheMergedOne() {
        UUID a = group("A");
        expense(a, haushalt, "10.00");
        expense(a, drogerie, "5.50");
        expense(a, obst, "3.00");
        migrate();
        var sums = new java.util.HashMap<UUID, java.math.BigDecimal>();
        db.query("select kategorie_id, sum(betrag) s from buchung_position where wohngruppe_id = ? group by kategorie_id",
                rs -> { sums.put(rs.getObject("kategorie_id", UUID.class), rs.getBigDecimal("s")); }, a);
        assertThat(sums).hasSize(2);
        assertThat(sums.get(haushalt)).isEqualByComparingTo("15.50");
        assertThat(sums.get(obst)).isEqualByComparingTo("3.00");
        // auch die Kategorie an der Buchung selbst (Altspalte) zieht mit
        assertThat(db.queryForObject("select count(*) from buchung where wohngruppe_id = ? and kategorie_id = ?", Integer.class, a, haushalt)).isEqualTo(2);
    }

    @Test
    void nothingPointsAtTheRemovedCategoryAnymore() {
        UUID a = group("A");
        expense(a, drogerie, "5.50");
        ownCategory(a, "Reinigung", drogerie);
        hide(a, drogerie);
        migrate();
        assertThat(db.queryForObject("select count(*) from buchung where kategorie_id = ?", Integer.class, drogerie)).isZero();
        assertThat(db.queryForObject("select count(*) from buchung_position where kategorie_id = ?", Integer.class, drogerie)).isZero();
        assertThat(db.queryForObject("select count(*) from kategorie_gruppe where parent_id = ? or global_ref = ?", Integer.class, drogerie, drogerie)).isZero();
        assertThat(db.queryForObject("select count(*) from buchung where kategorie_id = ?", Integer.class, haushalt)).isEqualTo(1);
    }

    @Test
    void ownSubcategoriesMoveUnderTheMergedCategory() {
        UUID a = group("A");
        UUID reinigung = ownCategory(a, "Reinigung", drogerie);
        UUID werkzeug = ownCategory(a, "Werkzeug", haushalt);
        migrate();
        assertThat(db.queryForObject("select parent_id from kategorie_gruppe where id = ?", UUID.class, reinigung)).isEqualTo(haushalt);
        assertThat(db.queryForObject("select parent_id from kategorie_gruppe where id = ?", UUID.class, werkzeug)).isEqualTo(haushalt);
    }

    @Test
    void theMergedCategoryIsVisibleIfEitherWasVisible() {
        UUID bothVisible = group("beide sichtbar");
        UUID onlyHaushaltHidden = group("Haushalt ausgeblendet");
        UUID onlyDrogerieHidden = group("Drogerie ausgeblendet");
        UUID bothHidden = group("beide ausgeblendet");
        hide(onlyHaushaltHidden, haushalt);
        hide(onlyDrogerieHidden, drogerie);
        hide(bothHidden, haushalt);
        hide(bothHidden, drogerie);
        migrate();
        assertThat(hiddenMerged(bothVisible)).isFalse();
        assertThat(hiddenMerged(onlyHaushaltHidden)).isFalse();
        assertThat(hiddenMerged(onlyDrogerieHidden)).isFalse();
        assertThat(hiddenMerged(bothHidden)).isTrue();
    }

    @Test
    void aGroupsOwnCategoryWithTheNewNameIsRenamedSoNamesStayUnique() {
        UUID a = group("A");
        UUID own = ownCategory(a, "haushalt und drogerie", null);
        migrate();
        assertThat(db.queryForObject("select name from kategorie_gruppe where id = ?", String.class, own)).isEqualTo("haushalt und drogerie (eigene)");
    }

    @Test
    void otherGroupsAndCategoriesAreUntouched() {
        UUID a = group("A");
        UUID b = group("B");
        expense(a, obst, "4.00");
        expense(b, categoryId("Fahrten"), "9.00");
        hide(b, categoryId("Sonstiges"));
        migrate();
        assertThat(db.queryForObject("select count(*) from buchung_position where kategorie_id = ?", Integer.class, obst)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from buchung_position where kategorie_id = ?", Integer.class, categoryId("Fahrten"))).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from kategorie_gruppe where global_ref = ? and ausgeblendet", Integer.class, categoryId("Sonstiges"))).isEqualTo(1);
        List<String> names = db.queryForList("select name from kategorie_global where parent_id is null order by name", String.class);
        assertThat(names).containsExactly("Anschaffungen", "Fahrten", "Freizeit und Ausflüge", "Geschenke und Feiern", NEW_NAME, "Lebensmittel", "Sonstiges");
    }

    @Test
    void migratingAnEmptyDatabaseWorks() {
        migrate();
        assertThat(db.queryForObject("select count(*) from kategorie_global", Integer.class)).isEqualTo(17);
    }
}
