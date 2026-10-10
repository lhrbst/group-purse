package de.grouppurse.export;

import de.grouppurse.category.CategoryTree;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungPosition;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Text zur Kategorie einer Buchung für CSV und PDF: eine Position als Name, mehrere als "Name (Betrag) | Name (Betrag)". */
final class ExportPositions {
    private ExportPositions() {
    }

    static String describe(Buchung b, Map<UUID, List<BuchungPosition>> positions, CategoryTree tree) {
        List<BuchungPosition> list = positions.getOrDefault(b.id, List.of());
        if (list.isEmpty()) {
            return b.kategorieId == null ? "" : tree.path(b.kategorieId);
        }
        if (list.size() == 1) {
            return tree.path(list.get(0).kategorieId);
        }
        return list.stream().map(p -> tree.path(p.kategorieId) + " (" + ExportFormat.number(p.betrag) + ")").collect(Collectors.joining(" | "));
    }
}
