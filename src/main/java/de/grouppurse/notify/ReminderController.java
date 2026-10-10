package de.grouppurse.notify;

import de.grouppurse.security.Access;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/contributions")
public class ReminderController {
    public record ReminderResult(int gesendet) {
    }

    private final Access access;
    private final ReminderService reminders;

    public ReminderController(Access access, ReminderService reminders) {
        this.access = access;
        this.reminders = reminders;
    }

    @PostMapping("/reminders")
    public ReminderResult send() {
        access.requireKassenverwalter();
        return new ReminderResult(reminders.sendOverdueReminders(LocalDate.now()));
    }
}
