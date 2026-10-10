package de.grouppurse.security;

import java.io.Serializable;
import java.util.UUID;

public record AppPrincipal(UUID personId, String name, String email, boolean istAdmin) implements Serializable {
}
