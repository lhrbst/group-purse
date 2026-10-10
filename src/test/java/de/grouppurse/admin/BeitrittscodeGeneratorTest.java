package de.grouppurse.admin;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BeitrittscodeGeneratorTest {
    @Test
    void codesHaveEightAllowedCharacters() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String c = BeitrittscodeGenerator.generate();
            assertThat(c).hasSize(8).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}");
            seen.add(c);
        }
        assertThat(seen.size()).isGreaterThan(990);
    }
}
