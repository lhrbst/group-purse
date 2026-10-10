package de.grouppurse.expense;

import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Rolle;
import de.grouppurse.expense.ExpenseDtos.ExpenseInput;
import de.grouppurse.expense.ExpenseDtos.RejectRequest;
import de.grouppurse.ledger.BuchungDto;
import de.grouppurse.ledger.BuchungMapper;
import de.grouppurse.security.Access;
import de.grouppurse.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ExpenseController {
    private final Access access;
    private final ExpenseService expenses;
    private final ReceiptStorage storage;
    private final CurrentUser user;
    private final BuchungMapper mapper;

    public ExpenseController(Access access, ExpenseService expenses, ReceiptStorage storage, CurrentUser user, BuchungMapper mapper) {
        this.access = access;
        this.expenses = expenses;
        this.storage = storage;
        this.user = user;
        this.mapper = mapper;
    }

    @PostMapping(value = "/expenses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BuchungDto submit(@Valid @RequestPart("data") ExpenseInput data,
                             @RequestPart(value = "photos", required = false) List<MultipartFile> photos) {
        access.requireActiveMember();
        return mapper.toDto(expenses.submit(data, photos));
    }

    @PutMapping(value = "/expenses/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BuchungDto resubmit(@PathVariable UUID id, @Valid @RequestPart("data") ExpenseInput data,
                               @RequestPart(value = "photos", required = false) List<MultipartFile> photos) {
        access.requireActiveMember();
        return mapper.toDto(expenses.resubmit(id, data, photos));
    }

    @PostMapping(value = "/ledger/kontoausgabe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BuchungDto kontoausgabe(@Valid @RequestPart("data") ExpenseInput data,
                                   @RequestPart(value = "photos", required = false) List<MultipartFile> photos) {
        access.requireKassenverwalter();
        return mapper.toDto(expenses.kontoausgabe(data, photos));
    }

    @PostMapping("/expenses/{id}/approve")
    public BuchungDto approve(@PathVariable UUID id) {
        access.requireKassenverwalter();
        return mapper.toDto(expenses.approve(id));
    }

    @PostMapping("/expenses/{id}/reject")
    public BuchungDto reject(@PathVariable UUID id, @RequestBody RejectRequest r) {
        access.requireKassenverwalter();
        return mapper.toDto(expenses.reject(id, r.kommentar()));
    }

    @GetMapping("/expenses/mine")
    public List<BuchungDto> mine(@RequestParam(defaultValue = "30") int days) {
        access.requireActiveMember();
        return expenses.mine(Math.min(Math.max(days, 1), 3650));
    }

    @GetMapping("/expenses/pending")
    public List<BuchungDto> pending() {
        access.requireKassenverwalter();
        return expenses.pending();
    }

    @GetMapping("/expenses")
    public List<BuchungDto> search(@RequestParam(required = false) BuchungStatus status,
                                   @RequestParam(required = false) UUID kategorie,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate von,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bis,
                                   @RequestParam(required = false) UUID person,
                                   @RequestParam(required = false) String bewohner) {
        access.requireKassenverwalter();
        return expenses.search(status, kategorie, von, bis, person, bewohner);
    }

    @GetMapping("/expenses/{id}")
    public BuchungDto get(@PathVariable UUID id) {
        Rolle rolle = access.requireActiveMember();
        return expenses.get(id, rolle == Rolle.KASSENVERWALTER);
    }

    /** Beleg ausliefern: nur Kassenverwalter (inkl. Admin) und die erfassende Person. */
    @GetMapping("/receipts/{buchungId}/{index}")
    public ResponseEntity<Resource> receipt(@PathVariable UUID buchungId, @PathVariable int index) {
        Rolle rolle = access.requireActiveMember();
        Buchung b = expenses.find(buchungId);
        if (rolle != Rolle.KASSENVERWALTER && !b.erfasstVon.equals(user.personId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diesen Beleg");
        }
        if (index < 0 || index >= b.belegFotos.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Beleg nicht gefunden");
        }
        String name = b.belegFotos.get(index);
        Resource res = storage.load(name);
        if (res == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Beleg nicht gefunden");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReceiptStorage.contentType(name)))
                .cacheControl(CacheControl.noStore())
                .body(res);
    }
}
