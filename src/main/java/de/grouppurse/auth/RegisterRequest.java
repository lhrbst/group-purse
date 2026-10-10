package de.grouppurse.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(@NotBlank String name, @NotBlank @Email String email, @NotBlank String password,
                              @NotBlank String beitrittscode, Boolean datenschutzZugestimmt) {
}
