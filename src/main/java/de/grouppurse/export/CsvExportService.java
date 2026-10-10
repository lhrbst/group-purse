package de.grouppurse.export;

import de.grouppurse.category.CategoryService;
import de.grouppurse.category.CategoryTree;
import de.grouppurse.domain.BuchungPosition;
import de.grouppurse.repo.BuchungPositionRepository;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.Person;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CsvExportService {
    private static final String HEADER = "Nummer;Datum;Konto;Art;Betrag;Kategorie;Zweck;Händler;Bewohner;Status;Erfasst von;Erfasst am;Storno von Nr.;Beleg-Fotos;Begründung ohne Beleg";

    private final BuchungRepository buchungen;
    private final PersonRepository people;
    private final CategoryService categories;
    private final BuchungPositionRepository positionRepo;

    public CsvExportService(BuchungRepository buchungen, PersonRepository people, CategoryService categories,
                            BuchungPositionRepository positionRepo) {
        this.buchungen = buchungen;
        this.people = people;
        this.categories = categories;
        this.positionRepo = positionRepo;
    }

    /** Text als Excel-sichere Zelle: immer in Anführungszeichen, Formelzeichen am Anfang werden entschärft. */
    static String text(String s) {
        if (s == null || s.isEmpty()) {
            return "\"\"";
        }
        String v = s.replace("\r", " ").replace("\n", " ");
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t') {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    @Transactional(readOnly = true)
    public void write(int year, OutputStream out) throws IOException {
        List<Buchung> all = buchungen.findAll();
        Map<UUID, Long> numberById = all.stream().collect(Collectors.toMap(b -> b.id, b -> b.nummer));
        CategoryTree tree = categories.tree();
        Map<UUID, List<BuchungPosition>> positions = all.isEmpty() ? Map.of() : positionRepo.findByBuchungIdInOrderByPositionAsc(all.stream().map(b -> b.id).toList())
                .stream().collect(Collectors.groupingBy(p -> p.buchungId));
        Map<UUID, String> personNames = new HashMap<>();
        for (Person p : people.findAllById(all.stream().map(b -> b.erfasstVon).distinct().toList())) {
            personNames.put(p.id, p.name);
        }
        StringBuilder sb = new StringBuilder("﻿").append(HEADER).append("\r\n");
        all.stream().filter(b -> b.datum.getYear() == year).sorted(Comparator.comparingLong(b -> b.nummer)).forEach(b -> {
            sb.append(b.nummer).append(';')
                    .append(ExportFormat.date(b.datum)).append(';')
                    .append(b.konto == null ? "" : b.konto.name()).append(';')
                    .append(b.art.name()).append(';')
                    .append(ExportFormat.number(b.betrag)).append(';')
                    .append(text(ExportPositions.describe(b, positions, tree))).append(';')
                    .append(text(b.zweck)).append(';')
                    .append(text(b.haendler)).append(';')
                    .append(text(b.bewohnerKuerzel)).append(';')
                    .append(b.status.name()).append(';')
                    .append(text(personNames.get(b.erfasstVon))).append(';')
                    .append(ExportFormat.dateTime(b.erfasstAm)).append(';')
                    .append(b.stornoVon == null ? "" : String.valueOf(numberById.get(b.stornoVon))).append(';')
                    .append(b.belegFotos.size()).append(';')
                    .append(text(b.begruendungOhneBeleg)).append("\r\n");
        });
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
