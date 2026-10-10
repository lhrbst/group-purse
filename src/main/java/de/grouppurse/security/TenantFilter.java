package de.grouppurse.security;

import de.grouppurse.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Setzt die aktive Wohngruppe aus der Session als Mandant, aber nur solange der Zugriff noch besteht. */
public class TenantFilter extends OncePerRequestFilter {
    private final CurrentUser user;

    public TenantFilter(CurrentUser user) {
        this.user = user;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (user.principal().isPresent()) {
                user.activeGroup()
                        .filter(g -> user.roleIn(g).isPresent())
                        .ifPresent(TenantContext::set);
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
