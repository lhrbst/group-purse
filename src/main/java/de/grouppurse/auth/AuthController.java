package de.grouppurse.auth;

import de.grouppurse.auth.AuthDtos.ActiveGroupRequest;
import de.grouppurse.auth.AuthDtos.LoginRequest;
import de.grouppurse.auth.AuthDtos.MeDto;
import de.grouppurse.auth.AuthDtos.PasswordResetConfirm;
import de.grouppurse.auth.AuthDtos.PasswordResetRequest;
import de.grouppurse.domain.Person;
import de.grouppurse.repo.PersonRepository;
import de.grouppurse.security.AppPrincipal;
import de.grouppurse.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AuthController {
    /** Gültiger BCrypt-Hash, damit unbekannte E-Mails gleich lange dauern wie falsche Passwörter. */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BUuvGbbLzXjSDz5F3N0Ae7p3KQpS";

    private final PersonRepository people;
    private final PasswordEncoder encoder;
    private final SecurityContextRepository contextRepository;
    private final LoginRateLimiter limiter;
    private final AuthService auth;
    private final PasswordResetService resets;

    public AuthController(PersonRepository people, PasswordEncoder encoder, SecurityContextRepository contextRepository,
                          LoginRateLimiter limiter, AuthService auth, PasswordResetService resets) {
        this.people = people;
        this.encoder = encoder;
        this.contextRepository = contextRepository;
        this.limiter = limiter;
        this.auth = auth;
        this.resets = resets;
    }

    @PostMapping("/auth/login")
    public MeDto login(@Valid @RequestBody LoginRequest r, HttpServletRequest req, HttpServletResponse res) {
        String email = AuthService.normalizeEmail(r.email());
        if (limiter.isBlocked(email)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Zu viele Fehlversuche, bitte später erneut versuchen");
        }
        Person p = people.findByEmail(email).orElse(null);
        boolean passwordOk = encoder.matches(r.password(), p != null ? p.passwortHash : DUMMY_HASH);
        if (p == null || !passwordOk) {
            limiter.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-Mail oder Passwort falsch");
        }
        limiter.reset(email);
        startSession(p, req, res);
        return auth.me();
    }

    /** Legt die Session an (neue Session-ID gegen Session-Fixation) und setzt ggf. die Standardgruppe. */
    public void startSession(Person p, HttpServletRequest req, HttpServletResponse res) {
        AppPrincipal principal = AuthService.principalOf(p);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        String[] roles = p.istAdmin ? new String[]{"ROLE_USER", "ROLE_ADMIN"} : new String[]{"ROLE_USER"};
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                AuthorityUtils.createAuthorityList(roles)));
        SecurityContextHolder.setContext(ctx);
        if (req.getSession(false) != null) {
            req.changeSessionId();
        }
        HttpSession session = req.getSession(true);
        session.removeAttribute(CurrentUser.ACTIVE_GROUP);
        auth.defaultGroup(p).ifPresent(g -> session.setAttribute(CurrentUser.ACTIVE_GROUP, g));
        contextRepository.saveContext(ctx, req, res);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) {
            s.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public MeDto me() {
        return auth.me();
    }

    @PostMapping("/me/active-group")
    public MeDto setActiveGroup(@RequestBody ActiveGroupRequest r, HttpServletRequest req) {
        if (r.wohngruppeId() == null || !auth.mayActivate(r.wohngruppeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Wohngruppe");
        }
        req.getSession(true).setAttribute(CurrentUser.ACTIVE_GROUP, r.wohngruppeId());
        return auth.me();
    }

    @PostMapping("/auth/password-reset/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestReset(@Valid @RequestBody PasswordResetRequest r) {
        try {
            resets.request(r.email());
        } catch (RuntimeException e) {
            // Antwort darf nicht verraten, ob die Adresse existiert oder der Mailversand klappt
        }
    }

    @PostMapping("/auth/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmReset(@Valid @RequestBody PasswordResetConfirm r) {
        resets.confirm(r.token(), r.newPassword());
    }
}
