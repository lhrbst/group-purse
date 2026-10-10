package de.grouppurse.auth;

import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Rolle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record ActiveGroupRequest(UUID wohngruppeId) {
    }

    public record PasswordResetConfirm(@NotBlank String token,
                                       @NotBlank @Size(min = 10, message = "Passwort braucht mindestens 10 Zeichen") String newPassword) {
    }

    public record MitgliedschaftDto(UUID mitgliedschaftId, UUID wohngruppeId, String wohngruppeName, Rolle rolle, MitgliedStatus status) {
    }

    public record MeDto(UUID personId, String name, String email, boolean istAdmin,
                        List<MitgliedschaftDto> mitgliedschaften, UUID activeGroupId, Rolle activeRole) {
    }
}
