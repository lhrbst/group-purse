package de.grouppurse.category;

import java.util.UUID;

public record CategoryDto(UUID id, String name, boolean global, boolean ausgeblendet, UUID parentId) {
}
