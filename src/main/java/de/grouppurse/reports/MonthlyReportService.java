package de.grouppurse.reports;

import de.grouppurse.category.CategoryService;
import de.grouppurse.category.CategoryTree;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungPosition;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.reports.ReportDtos.MonthlyBeleg;
import de.grouppurse.reports.ReportDtos.MonthlyCategory;
import de.grouppurse.reports.ReportDtos.MonthlyPosition;
import de.grouppurse.reports.ReportDtos.MonthlyReport;
import de.grouppurse.repo.BuchungPositionRepository;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.PersonRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.tenant.TenantContext;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Monatsbericht der Ausgaben: Zahlen, Aufschlüsselung nach Kategorie, Einzelliste und Fließtext. Grundlage für Anzeige und PDF. */
@Service
@Transactional(readOnly = true)
public class MonthlyReportService {
    /** Nur Ausgaben mit feststehendem Betrag; VERARBEITUNG, MANUELL, ABGELEHNT und STORNIERT zählen nicht. */
    private static final List<BuchungStatus> COUNTED = List.of(BuchungStatus.GEBUCHT, BuchungStatus.EINGEREICHT, BuchungStatus.GEPRUEFT);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BuchungRepository buchungen;
    private final BuchungPositionRepository positions;
    private final CategoryService categories;
    private final PersonRepository people;
    private final WohngruppeRepository gruppen;

    public MonthlyReportService(BuchungRepository buchungen, BuchungPositionRepository positions, CategoryService categories,
                                PersonRepository people, WohngruppeRepository gruppen) {
        this.buchungen = buchungen;
        this.positions = positions;
        this.categories = categories;
        this.people = people;
        this.gruppen = gruppen;
    }

    private record Part(UUID kategorieId, BigDecimal betrag) {
    }

    private record Counted(Buchung buchung, List<Part> parts, BigDecimal total) {
    }

    public MonthlyReport report(YearMonth monat) {
        CategoryTree tree = categories.tree();
        List<Counted> current = load(monat);
        BigDecimal summe = total(current);
        BigDecimal vormonat = total(load(monat.minusMonths(1)));
        BigDecimal aenderung = summe.subtract(vormonat);
        BigDecimal aenderungProzent = vormonat.signum() == 0 ? null : aenderung.multiply(HUNDRED).divide(vormonat, 1, RoundingMode.HALF_UP);
        String gruppe = gruppen.findById(TenantContext.get().orElseThrow()).orElseThrow().name;

        Map<UUID, String> names = people.findAllById(current.stream().map(c -> c.buchung().erfasstVon).distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.id, p -> p.name));
        List<MonthlyBeleg> belege = current.stream().map(c -> {
            Buchung b = c.buchung();
            return new MonthlyBeleg(b.datum, b.nummer, b.konto, b.haendler, names.get(b.erfasstVon), c.total(),
                    b.status == BuchungStatus.EINGEREICHT,
                    c.parts().stream().map(p -> new MonthlyPosition(tree.name(p.kategorieId()), p.betrag())).toList());
        }).toList();

        Map<UUID, BigDecimal> perCategory = new HashMap<>();
        Map<String, BigDecimal> perMain = new HashMap<>();
        for (Counted c : current) {
            for (Part p : c.parts()) {
                perCategory.merge(p.kategorieId(), p.betrag(), BigDecimal::add);
                perMain.merge(tree.mainName(p.kategorieId()), p.betrag(), BigDecimal::add);
            }
        }
        List<MonthlyCategory> kategorien = perCategory.entrySet().stream().map(e -> {
            UUID id = e.getKey();
            String sub = Objects.equals(tree.mainId(id), id) ? null : tree.name(id);
            return new MonthlyCategory(tree.mainName(id), sub, e.getValue(), share(e.getValue(), summe));
        }).sorted(Comparator.comparing(MonthlyCategory::betrag, Comparator.reverseOrder())
                .thenComparing(MonthlyCategory::hauptkategorie)
                .thenComparing(c -> c.unterkategorie() == null ? "" : c.unterkategorie())).toList();

        List<Counted> unchecked = current.stream().filter(c -> c.buchung().status == BuchungStatus.EINGEREICHT).toList();
        return new MonthlyReport(monat.toString(), gruppe, summe, current.size(), vormonat, aenderung, aenderungProzent,
                unchecked.size(), total(unchecked), summary(monat, summe, current.size(), vormonat, aenderung, aenderungProzent, perMain),
                kategorien, belege);
    }

    private List<Counted> load(YearMonth monat) {
        Specification<Buchung> spec = (r, q, cb) -> cb.and(
                r.get("art").in(Art.AUSGABE, Art.KONTOAUSGABE),
                r.get("status").in(COUNTED),
                cb.greaterThanOrEqualTo(r.<LocalDate>get("datum"), monat.atDay(1)),
                cb.lessThanOrEqualTo(r.<LocalDate>get("datum"), monat.atEndOfMonth()));
        List<Buchung> rows = buchungen.findAll(spec, Sort.by("datum", "nummer"));
        Map<UUID, List<BuchungPosition>> byBooking = rows.isEmpty() ? Map.of()
                : positions.findByBuchungIdInOrderByPositionAsc(rows.stream().map(b -> b.id).toList()).stream()
                .collect(Collectors.groupingBy(p -> p.buchungId));
        List<Counted> result = new ArrayList<>();
        for (Buchung b : rows) {
            List<BuchungPosition> ps = byBooking.getOrDefault(b.id, List.of());
            // Altdaten ohne Positionen zählen mit ihrem Buchungsbetrag
            List<Part> parts = ps.isEmpty() ? List.of(new Part(b.kategorieId, b.betrag.negate()))
                    : ps.stream().map(p -> new Part(p.kategorieId, p.betrag)).toList();
            result.add(new Counted(b, parts, parts.stream().map(Part::betrag).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        return result;
    }

    private static BigDecimal total(List<Counted> list) {
        return list.stream().map(Counted::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return part.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static String summary(YearMonth monat, BigDecimal summe, int anzahl, BigDecimal vormonat, BigDecimal aenderung,
                                  BigDecimal prozent, Map<String, BigDecimal> perMain) {
        if (anzahl == 0) {
            return "Keine Ausgaben in diesem Monat.";
        }
        String vor = GermanFormat.month(monat.minusMonths(1));
        StringBuilder s = new StringBuilder("Im ").append(GermanFormat.month(monat)).append(" wurden ")
                .append(GermanFormat.money(summe)).append(" für ").append(anzahl == 1 ? "1 Beleg" : anzahl + " Belege").append(" ausgegeben");
        if (vormonat.signum() == 0) {
            s.append("; im ").append(vor).append(" gab es keine Ausgaben");
        } else if (aenderung.signum() == 0) {
            s.append(", genauso viel wie im ").append(vor);
        } else {
            s.append(", ").append(GermanFormat.percent(prozent.abs())).append(aenderung.signum() > 0 ? " mehr" : " weniger")
                    .append(" als im ").append(vor).append(" (").append(GermanFormat.money(vormonat)).append(")");
        }
        s.append(". ");
        List<String> top = perMain.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(3)
                .map(e -> e.getKey() + " (" + GermanFormat.money(e.getValue()) + ", " + GermanFormat.percent(share(e.getValue(), summe)) + ")")
                .toList();
        s.append(top.size() == 1 ? "Der größte Posten war " : "Die größten Posten waren ").append(joinGerman(top)).append(".");
        return s.toString();
    }

    private static String joinGerman(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " und " + items.get(items.size() - 1);
    }
}
