package de.grouppurse.notify;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.contributions.ContributionDtos.ContributionRequest;
import de.grouppurse.contributions.ContributionDtos.FamilyRequest;
import de.grouppurse.contributions.ContributionService;
import de.grouppurse.contributions.FamilyService;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ReminderServiceTest extends AbstractIntegrationTest {
    @Autowired FamilyService families;
    @Autowired ContributionService contributions;
    @Autowired ReminderService reminders;

    @Test
    void mailsOnlyOverdueFamiliesWithAddress() {
        Wohngruppe g = t.group("Erinnerung");
        Person kv = t.person("kv", false);
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate today = LocalDate.of(2026, 3, 10);
        t.as(kv, g, () -> {
            families.create(new FamilyRequest("Müller", null, "mueller@example.org", 5, new BigDecimal("50.00"), start, null));
            families.create(new FamilyRequest("Ohnemail", null, null, 5, new BigDecimal("50.00"), start, null));
            var paid = families.create(new FamilyRequest("Pünktlich", null, "puenktlich@example.org", 5, new BigDecimal("50.00"), start, null));
            contributions.book(new ContributionRequest(paid.id(), new BigDecimal("150.00"), today, List.of("2026-01", "2026-02", "2026-03"), null));
            assertThat(reminders.sendOverdueReminders(today)).isEqualTo(1);
        });
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailService, times(1)).send(eq("mueller@example.org"), anyString(), text.capture());
        assertThat(text.getValue()).contains("Januar 2026").contains("Februar 2026").contains("März 2026");
        verify(mailService, never()).send(eq("puenktlich@example.org"), anyString(), anyString());
    }
}
