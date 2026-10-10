package de.grouppurse.ledger;

import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.Person;
import de.grouppurse.repo.PersonRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class BuchungMapper {
    private final PersonRepository people;

    public BuchungMapper(PersonRepository people) {
        this.people = people;
    }

    public List<BuchungDto> toDtos(Collection<Buchung> list) {
        Map<UUID, String> names = people.findAllById(list.stream().map(b -> b.erfasstVon).distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.id, p -> p.name));
        return list.stream().map(b -> toDto(b, names.get(b.erfasstVon))).toList();
    }

    public BuchungDto toDto(Buchung b) {
        String name = people.findById(b.erfasstVon).map(p -> p.name).orElse(null);
        return toDto(b, name);
    }

    private BuchungDto toDto(Buchung b, String name) {
        boolean expense = b.art == Art.AUSGABE || b.art == Art.KONTOAUSGABE;
        return new BuchungDto(b.id, b.nummer, b.konto, b.art, b.betrag, b.datum, b.kategorieId, b.zweck, b.haendler,
                b.bewohnerKuerzel, b.status, b.belegFotos.size(), expense && b.belegFotos.isEmpty(),
                b.begruendungOhneBeleg, b.ablehnungsKommentar, b.stornoVon, b.gruppeId, b.erfasstVon, name,
                b.erfasstAm, b.geprueftVon, b.geprueftAm);
    }
}
