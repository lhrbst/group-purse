package de.grouppurse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationTest extends AbstractIntegrationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allTablesExistAndCategoriesSeeded() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables where table_schema = 'public' and table_name in
                ('person','wohngruppe','mitgliedschaft','einladung','passwort_reset_token','kategorie_global',
                 'kategorie_gruppe','familie','sollbetrag','beitrag','buchung','audit_log','push_subscription')
                """, Integer.class);
        assertThat(tables).isEqualTo(13);
        assertThat(jdbc.queryForObject("select count(*) from kategorie_global", Integer.class)).isEqualTo(8);
    }
}
