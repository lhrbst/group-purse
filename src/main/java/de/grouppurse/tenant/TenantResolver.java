package de.grouppurse.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TenantResolver implements CurrentTenantIdentifierResolver<UUID> {
    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.get().orElse(TenantContext.NONE);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
