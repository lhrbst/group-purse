package de.grouppurse.receipt;

import de.grouppurse.category.CategoryDto;
import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Konto;
import de.grouppurse.ledger.BalanceService;
import de.grouppurse.receipt.ReceiptModelClient.RawPosition;
import de.grouppurse.receipt.ReceiptModelClient.RawReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiptRecognitionServiceTest {
    static final UUID OBST = UUID.randomUUID();
    static final UUID MILCH = UUID.randomUUID();
    static final UUID DROGERIE = UUID.randomUUID();
    static final UUID LEBENSMITTEL = UUID.randomUUID();
    static final byte[] IMG = {1, 2, 3};

    ReceiptModelClient client = mock(ReceiptModelClient.class);
    CategoryService categories = mock(CategoryService.class);
    BalanceService balances = mock(BalanceService.class);
    List<CategoryDto> leaves = List.of(
            new CategoryDto(OBST, "Obst und Gemüse", true, false, LEBENSMITTEL),
            new CategoryDto(MILCH, "Milch, Käse und Eier", true, false, LEBENSMITTEL),
            new CategoryDto(DROGERIE, "Drogerie und Hygiene", true, false, null));

    @BeforeEach
    void setUp() {
        when(categories.leaves()).thenReturn(leaves);
        when(balances.balance(Konto.BARKASSE)).thenReturn(new BigDecimal("200.00"));
    }

    ReceiptRecognitionService service(boolean enabled) {
        return new ReceiptRecognitionService(client, new ReceiptAiProperties(enabled, "klein", "gross"), categories, balances);
    }

    RawReceipt good() {
        return new RawReceipt("REWE", LocalDate.now().minusDays(1).toString(), new BigDecimal("23.40"),
                List.of(new RawPosition("obst und gemüse", new BigDecimal("8.40")),
                        new RawPosition("Lebensmittel › Milch, Käse und Eier", new BigDecimal("6.20")),
                        new RawPosition("Drogerie und Hygiene", new BigDecimal("8.80"))));
    }

    @Test
    void disabledDoesNotCallModel() {
        assertThat(service(false).recognize(IMG, "image/jpeg")).isEmpty();
        verify(client, never()).extract(any(), any(), any(), any());
    }

    @Test
    void splitsTheReceiptIntoSeveralCategories() {
        when(client.extract(any(), any(), any(), eq("klein"))).thenReturn(Optional.of(good()));
        var s = service(true).recognize(IMG, "image/jpeg");
        assertThat(s).isPresent();
        assertThat(s.get().positionen()).extracting(p -> p.kategorieId()).containsExactly(OBST, MILCH, DROGERIE);
        assertThat(s.get().positionen()).extracting(p -> p.betrag()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("8.40"), new BigDecimal("6.20"), new BigDecimal("8.80"));
        verify(client, times(1)).extract(any(), any(), any(), any());
    }

    @Test
    void onlyAssignableCategoriesAreOfferedToTheModel() {
        when(client.extract(any(), any(), any(), eq("klein"))).thenReturn(Optional.of(good()));
        service(true).recognize(IMG, "image/jpeg");
        verify(client).extract(any(), any(), eq(List.of("Obst und Gemüse", "Milch, Käse und Eier", "Drogerie und Hygiene")), eq("klein"));
    }

    @Test
    void positionsThatDoNotAddUpFallBackToStrongerModel() {
        var off = new RawReceipt("REWE", LocalDate.now().toString(), new BigDecimal("23.40"),
                List.of(new RawPosition("Obst und Gemüse", new BigDecimal("10.00"))));
        when(client.extract(any(), any(), any(), eq("klein"))).thenReturn(Optional.of(off));
        when(client.extract(any(), any(), any(), eq("gross"))).thenReturn(Optional.of(good()));
        var s = service(true).recognize(IMG, "image/jpeg");
        assertThat(s).isPresent();
        assertThat(s.get().positionen()).hasSize(3);
        verify(client, times(2)).extract(any(), any(), any(), any());
    }

    @Test
    void futureDateFallsBackToStrongerModel() {
        var broken = new RawReceipt("REWE", LocalDate.now().plusDays(3).toString(), new BigDecimal("23.40"),
                List.of(new RawPosition("Obst und Gemüse", new BigDecimal("23.40"))));
        when(client.extract(any(), any(), any(), eq("klein"))).thenReturn(Optional.of(broken));
        when(client.extract(any(), any(), any(), eq("gross"))).thenReturn(Optional.of(good()));
        var s = service(true).recognize(IMG, "image/jpeg");
        assertThat(s).isPresent();
        assertThat(s.get().datum()).isBefore(LocalDate.now());
    }

    @Test
    void exceptionInFirstCallStillTriesFallback() {
        when(client.extract(any(), any(), any(), eq("klein"))).thenThrow(new RuntimeException("boom"));
        when(client.extract(any(), any(), any(), eq("gross"))).thenReturn(Optional.of(good()));
        assertThat(service(true).recognize(IMG, "image/jpeg")).isPresent();
    }

    @Test
    void bothFailingGivesEmpty() {
        when(client.extract(any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(service(true).recognize(IMG, "image/jpeg")).isEmpty();
    }

    @Test
    void sameCategoryOnSeveralLinesIsMergedAndUnknownOnesAreKeptWithoutCategory() {
        var raw = new RawReceipt("Kiosk", "gestern", new BigDecimal("15.00"),
                List.of(new RawPosition("Obst und Gemüse", new BigDecimal("5.00")),
                        new RawPosition("Obst und Gemüse", new BigDecimal("4.00")),
                        new RawPosition("Ufo", new BigDecimal("6.00"))));
        var s = ReceiptRecognitionService.map(raw, leaves);
        assertThat(s.datum()).isNull();
        assertThat(s.positionen()).hasSize(2);
        assertThat(s.positionen().get(0).kategorieId()).isEqualTo(OBST);
        assertThat(s.positionen().get(0).betrag()).isEqualByComparingTo("9.00");
        assertThat(s.positionen().get(1).kategorieId()).isNull();
    }
}
