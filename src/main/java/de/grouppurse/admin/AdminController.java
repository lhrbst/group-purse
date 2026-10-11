package de.grouppurse.admin;

import de.grouppurse.admin.AdminDtos.CreateGroupRequest;
import de.grouppurse.admin.AdminDtos.InvitationInfo;
import de.grouppurse.admin.AdminDtos.InviteRequest;
import de.grouppurse.admin.AdminDtos.InviteResponse;
import de.grouppurse.admin.AdminDtos.RedeemRequest;
import de.grouppurse.admin.AdminDtos.WohngruppeDto;
import de.grouppurse.auth.AuthDtos.MeDto;
import de.grouppurse.auth.AuthService;
import de.grouppurse.domain.Person;
import de.grouppurse.metrics.GkMetrics;
import de.grouppurse.security.Access;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AdminController {
    private final Access access;
    private final WohngruppeService gruppen;
    private final InvitationService invitations;
    private final AuthService auth;
    private final GkMetrics metrics;

    public AdminController(Access access, WohngruppeService gruppen, InvitationService invitations, AuthService auth,
                           GkMetrics metrics) {
        this.access = access;
        this.gruppen = gruppen;
        this.invitations = invitations;
        this.auth = auth;
        this.metrics = metrics;
    }

    @PostMapping("/admin/wohngruppen")
    @ResponseStatus(HttpStatus.CREATED)
    public WohngruppeDto create(@Valid @RequestBody CreateGroupRequest r) {
        access.requireAdmin();
        return WohngruppeService.toDto(gruppen.create(r.name()), true);
    }

    @GetMapping("/admin/wohngruppen")
    public List<WohngruppeDto> list() {
        access.requireAdmin();
        return gruppen.list().stream().map(g -> WohngruppeService.toDto(g, true)).toList();
    }

    @PostMapping("/admin/wohngruppen/{id}/kassenverwalter-einladung")
    public InviteResponse invite(@PathVariable UUID id, @Valid @RequestBody InviteRequest r) {
        access.requireAdmin();
        return invitations.inviteKassenverwalter(id, r.email());
    }

    @GetMapping("/einladungen/{token}")
    public InvitationInfo info(@PathVariable String token) {
        return invitations.info(token);
    }

    @PostMapping("/einladungen/{token}/einloesen")
    public MeDto redeem(@PathVariable String token, @RequestBody RedeemRequest r,
                        HttpServletRequest req, HttpServletResponse res) {
        Person p;
        try {
            p = invitations.redeem(token, r);
        } catch (RuntimeException e) {
            metrics.invitation("rejected");
            throw e;
        }
        metrics.invitation("success");
        auth.startSession(p, req, res);
        return auth.me();
    }
}
