package de.grouppurse.members;

import de.grouppurse.admin.AdminDtos.WohngruppeDto;
import de.grouppurse.admin.WohngruppeService;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.members.MemberDtos.CodeResponse;
import de.grouppurse.members.MemberDtos.MemberDto;
import de.grouppurse.members.MemberDtos.SettingsRequest;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.security.Access;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class MemberController {
    private final Access access;
    private final MemberService members;
    private final WohngruppeService gruppenService;
    private final WohngruppeRepository gruppen;

    public MemberController(Access access, MemberService members, WohngruppeService gruppenService, WohngruppeRepository gruppen) {
        this.access = access;
        this.members = members;
        this.gruppenService = gruppenService;
        this.gruppen = gruppen;
    }

    @GetMapping("/members")
    public List<MemberDto> list() {
        access.requireKassenverwalter();
        return members.list(access.requireGroup());
    }

    @PostMapping("/members/{id}/freischalten")
    public MemberDto activate(@PathVariable UUID id) {
        access.requireKassenverwalter();
        return members.activate(access.requireGroup(), id);
    }

    @PostMapping("/members/{id}/sperren")
    public MemberDto block(@PathVariable UUID id) {
        access.requireKassenverwalter();
        return members.block(access.requireGroup(), id);
    }

    @GetMapping("/group")
    @Transactional(readOnly = true)
    public WohngruppeDto group() {
        Rolle rolle = access.requireActiveMember();
        Wohngruppe g = gruppen.findById(access.requireGroup()).orElseThrow();
        return WohngruppeService.toDto(g, rolle == Rolle.KASSENVERWALTER);
    }

    @PutMapping("/group/settings")
    @Transactional
    public WohngruppeDto settings(@Valid @RequestBody SettingsRequest r) {
        access.requireKassenverwalter();
        Wohngruppe g = gruppen.findById(access.requireGroup()).orElseThrow();
        g.mindestbetragKasse = r.mindestbetragKasse();
        return WohngruppeService.toDto(g, true);
    }

    @PostMapping("/group/beitrittscode/neu")
    public CodeResponse newCode() {
        access.requireKassenverwalter();
        return new CodeResponse(gruppenService.regenerateCode(access.requireGroup()));
    }
}
