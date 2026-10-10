package de.grouppurse.contributions;

import de.grouppurse.contributions.ContributionDtos.CancelRequest;
import de.grouppurse.contributions.ContributionDtos.ContributionDto;
import de.grouppurse.contributions.ContributionDtos.ContributionRequest;
import de.grouppurse.contributions.ContributionDtos.FamilyDto;
import de.grouppurse.contributions.ContributionDtos.FamilyRequest;
import de.grouppurse.contributions.ContributionDtos.GridDto;
import de.grouppurse.contributions.ContributionDtos.SollbetragDto;
import de.grouppurse.contributions.ContributionDtos.SollbetragRequest;
import de.grouppurse.contributions.ContributionDtos.Summary;
import de.grouppurse.security.Access;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ContributionController {
    private final Access access;
    private final FamilyService families;
    private final ContributionService contributions;
    private final GridService grid;

    public ContributionController(Access access, FamilyService families, ContributionService contributions, GridService grid) {
        this.access = access;
        this.families = families;
        this.contributions = contributions;
        this.grid = grid;
    }

    @GetMapping("/families")
    public List<FamilyDto> families() {
        access.requireKassenverwalter();
        return families.list();
    }

    @PostMapping("/families")
    @ResponseStatus(HttpStatus.CREATED)
    public FamilyDto createFamily(@Valid @RequestBody FamilyRequest r) {
        access.requireKassenverwalter();
        return families.create(r);
    }

    @PutMapping("/families/{id}")
    public FamilyDto updateFamily(@PathVariable UUID id, @Valid @RequestBody FamilyRequest r) {
        access.requireKassenverwalter();
        return families.update(id, r);
    }

    @PostMapping("/families/{id}/sollbetrag")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setSollbetrag(@PathVariable UUID id, @Valid @RequestBody SollbetragRequest r) {
        access.requireKassenverwalter();
        families.setSollbetrag(id, r.betrag(), r.gueltigAb());
    }

    @GetMapping("/families/{id}/sollbetraege")
    public List<SollbetragDto> history(@PathVariable UUID id) {
        access.requireKassenverwalter();
        return families.history(id);
    }

    @PostMapping("/contributions")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ContributionDto> book(@Valid @RequestBody ContributionRequest r) {
        access.requireKassenverwalter();
        var b = contributions.book(r);
        return contributions.list(b.familieId).stream().filter(c -> c.id().equals(b.id)).toList();
    }

    @GetMapping("/contributions")
    public List<ContributionDto> list(@RequestParam(required = false) UUID familie) {
        access.requireKassenverwalter();
        return contributions.list(familie);
    }

    @PostMapping("/contributions/{id}/storno")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id, @RequestBody CancelRequest r) {
        access.requireKassenverwalter();
        contributions.cancel(id, r.begruendung());
    }

    @GetMapping("/contributions/grid")
    public GridDto grid(@RequestParam(required = false) String von, @RequestParam(required = false) String bis) {
        access.requireKassenverwalter();
        try {
            YearMonth to = bis != null ? YearMonth.parse(bis) : YearMonth.now();
            YearMonth from = von != null ? YearMonth.parse(von) : to.minusMonths(11);
            return grid.grid(from, to, LocalDate.now());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Monat erwartet im Format JJJJ-MM");
        }
    }

    @GetMapping("/contributions/summary")
    public Summary summary() {
        access.requireKassenverwalter();
        return grid.summary(LocalDate.now());
    }
}
