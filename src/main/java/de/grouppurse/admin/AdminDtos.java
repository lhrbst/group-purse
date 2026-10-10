package de.grouppurse.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.UUID;

public final class AdminDtos {
    private AdminDtos() {
    }

    public record CreateGroupRequest(@NotBlank String name) {
    }

    public record WohngruppeDto(UUID id, String name, String beitrittscode, BigDecimal mindestbetragKasse) {
    }

    public record InviteRequest(@NotBlank @Email String email) {
    }

    public record InviteResponse(String link) {
    }

    public record InvitationInfo(String email, String wohngruppeName, boolean kontoExistiert) {
    }

    public record RedeemRequest(String name, String password, Boolean datenschutzZugestimmt) {
    }
}
