package de.grouppurse.members;

import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Rolle;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public final class MemberDtos {
    private MemberDtos() {
    }

    public record MemberDto(UUID mitgliedschaftId, UUID personId, String name, String email, Rolle rolle, MitgliedStatus status) {
    }

    public record SettingsRequest(@NotNull @DecimalMin("0.00") BigDecimal mindestbetragKasse) {
    }

    public record CodeResponse(String beitrittscode) {
    }

    public record LinkResponse(String link) {
    }
}
