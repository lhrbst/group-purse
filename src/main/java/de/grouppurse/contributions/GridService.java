package de.grouppurse.contributions;

import de.grouppurse.contributions.ContributionDtos.Cell;
import de.grouppurse.contributions.ContributionDtos.CellStatus;
import de.grouppurse.contributions.ContributionDtos.GridDto;
import de.grouppurse.contributions.ContributionDtos.GridRow;
import de.grouppurse.contributions.ContributionDtos.Summary;
import de.grouppurse.domain.Beitrag;
import de.grouppurse.domain.Familie;
import de.grouppurse.domain.Sollbetrag;
import de.grouppurse.repo.BeitragRepository;
import de.grouppurse.repo.FamilieRepository;
import de.grouppurse.repo.SollbetragRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Raster Familie x Monat. Alle Zahlungen einer Familie bilden einen Topf, der die ältesten offenen Monate zuerst füllt;
 * was darüber hinausgeht, ist Guthaben.
 */
@Service
public class GridService {
    private final FamilieRepository familien;
    private final SollbetragRepository sollbetraege;
    private final BeitragRepository beitraege;

    public GridService(FamilieRepository familien, SollbetragRepository sollbetraege, BeitragRepository beitraege) {
        this.familien = familien;
        this.sollbetraege = sollbetraege;
        this.beitraege = beitraege;
    }

    @Transactional(readOnly = true)
    public GridDto grid(YearMonth von, YearMonth bis, LocalDate heute) {
        if (bis.isBefore(von)) {
            throw new IllegalArgumentException("Ende liegt vor dem Beginn");
        }
        if (von.until(bis, java.time.temporal.ChronoUnit.MONTHS) > 120) {
            throw new IllegalArgumentException("Zeitraum zu groß (höchstens 10 Jahre)");
        }
        Map<UUID, List<Sollbetrag>> soll = sollbetraege.findAll().stream().collect(Collectors.groupingBy(s -> s.familieId));
        Map<UUID, BigDecimal> paid = new HashMap<>();
        for (Beitrag b : beitraege.findByStorniertFalse()) {
            paid.merge(b.familieId, b.betrag, BigDecimal::add);
        }
        YearMonth horizon = bis.isAfter(YearMonth.from(heute)) ? bis : YearMonth.from(heute);

        List<String> months = new ArrayList<>();
        for (YearMonth m = von; !m.isAfter(bis); m = m.plusMonths(1)) {
            months.add(m.toString());
        }

        List<GridRow> rows = new ArrayList<>();
        List<Familie> sorted = familien.findAll().stream()
                .sorted(Comparator.comparing((Familie f) -> f.name, String.CASE_INSENSITIVE_ORDER)).toList();
        for (Familie f : sorted) {
            BigDecimal total = paid.getOrDefault(f.id, BigDecimal.ZERO);
            if (!f.aktiv && total.signum() == 0) {
                continue;
            }
            List<Sollbetrag> history = soll.getOrDefault(f.id, List.of());
            rows.add(row(f, history, total, von, bis, horizon, heute));
        }
        return new GridDto(months, rows);
    }

    private GridRow row(Familie f, List<Sollbetrag> history, BigDecimal total, YearMonth von, YearMonth bis,
                        YearMonth horizon, LocalDate heute) {
        Map<YearMonth, BigDecimal> sollByMonth = new HashMap<>();
        Map<YearMonth, BigDecimal> paidByMonth = new HashMap<>();
        BigDecimal remaining = total;
        if (!history.isEmpty()) {
            YearMonth start = history.stream().map(s -> YearMonth.from(s.gueltigAb)).min(Comparator.naturalOrder()).orElseThrow();
            for (YearMonth m = start; !m.isAfter(horizon); m = m.plusMonths(1)) {
                BigDecimal s = FamilyService.sollFor(history, m);
                BigDecimal pay = remaining.min(s);
                sollByMonth.put(m, s);
                paidByMonth.put(m, pay);
                remaining = remaining.subtract(pay);
            }
        }
        List<Cell> cells = new ArrayList<>();
        for (YearMonth m = von; !m.isAfter(bis); m = m.plusMonths(1)) {
            cells.add(cell(m, sollByMonth.getOrDefault(m, BigDecimal.ZERO), paidByMonth.getOrDefault(m, BigDecimal.ZERO),
                    f.faelligkeitstag, heute));
        }
        int overdue = 0;
        for (YearMonth m : sollByMonth.keySet()) {
            if (!m.isAfter(YearMonth.from(heute)) && cell(m, sollByMonth.get(m), paidByMonth.get(m), f.faelligkeitstag, heute).status() == CellStatus.UEBERFAELLIG) {
                overdue++;
            }
        }
        return new GridRow(f.id, f.name, f.bewohnerKuerzel, f.aktiv, f.faelligkeitstag, cells, remaining, overdue);
    }

    static Cell cell(YearMonth m, BigDecimal soll, BigDecimal paid, int dueDay, LocalDate heute) {
        CellStatus status;
        if (soll.signum() == 0) {
            status = CellStatus.KEIN_SOLL;
        } else if (paid.compareTo(soll) >= 0) {
            status = CellStatus.BEZAHLT;
        } else if (heute.isAfter(m.atDay(dueDay))) {
            status = CellStatus.UEBERFAELLIG;
        } else if (paid.signum() > 0) {
            status = CellStatus.TEILWEISE;
        } else {
            status = CellStatus.OFFEN;
        }
        return new Cell(m.toString(), soll, paid, status);
    }

    /** Für Startseite und Erinnerungen: Familien mit überfälligen bzw. offenen Monaten bis heute. */
    @Transactional(readOnly = true)
    public Summary summary(LocalDate heute) {
        YearMonth now = YearMonth.from(heute);
        GridDto g = grid(now.minusYears(10), now, heute);
        int overdue = 0;
        int open = 0;
        for (GridRow r : g.zeilen()) {
            if (!r.aktiv()) {
                continue;
            }
            if (r.cells().stream().anyMatch(c -> c.status() == CellStatus.UEBERFAELLIG)) {
                overdue++;
            } else if (r.cells().stream().anyMatch(c -> c.status() == CellStatus.OFFEN || c.status() == CellStatus.TEILWEISE)) {
                open++;
            }
        }
        return new Summary((int) g.zeilen().stream().filter(GridRow::aktiv).count(), overdue, open);
    }
}
