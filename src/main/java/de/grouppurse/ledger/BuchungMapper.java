package de.grouppurse.ledger;

import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungPosition;
import de.grouppurse.repo.BuchungPositionRepository;
import de.grouppurse.repo.PersonRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class BuchungMapper {
    private final PersonRepository people;
    private final BuchungPositionRepository positionen;

    public BuchungMapper(PersonRepository people, BuchungPositionRepository positionen) {
        this.people = people;
        this.positionen = positionen;
    }

    public List<BuchungDto> toDtos(Collection<Buchung> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = people.findAllById(list.stream().map(b -> b.erfasstVon).distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.id, p -> p.name));
        Map<UUID, List<PositionDto>> byBooking = positionen.findByBuchungIdInOrderByPositionAsc(list.stream().map(b -> b.id).toList()).stream()
                .collect(Collectors.groupingBy(p -> p.buchungId, Collectors.mapping(BuchungMapper::position, Collectors.toList())));
        return list.stream().map(b -> toDto(b, names.get(b.erfasstVon), byBooking.getOrDefault(b.id, List.of()))).toList();
    }

    public BuchungDto toDto(Buchung b) {
        String name = people.findById(b.erfasstVon).map(p -> p.name).orElse(null);
        List<PositionDto> pos = positionen.findByBuchungIdInOrderByPositionAsc(Collections.singletonList(b.id)).stream().map(BuchungMapper::position).toList();
        return toDto(b, name, pos);
    }

    private static PositionDto position(BuchungPosition p) {
        return new PositionDto(p.kategorieId, p.betrag);
    }

    private BuchungDto toDto(Buchung b, String name, List<PositionDto> pos) {
        boolean expense = b.art == Art.AUSGABE || b.art == Art.KONTOAUSGABE;
        return new BuchungDto(b.id, b.nummer, b.konto, b.art, b.betrag, b.datum, b.kategorieId, b.zweck, b.haendler,
                b.bewohnerKuerzel, b.status, b.belegFotos.size(), expense && b.belegFotos.isEmpty(),
                b.begruendungOhneBeleg, b.ablehnungsKommentar, b.stornoVon, b.gruppeId, b.erfasstVon, name,
                b.erfasstAm, b.geprueftVon, b.geprueftAm, pos, b.verarbeitungFehler);
    }
}
