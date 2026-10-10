package de.grouppurse.receipt;

import de.grouppurse.category.CategoryDto;
import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Konto;
import de.grouppurse.ledger.BalanceService;
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
    static final UUID LEBENSMITTEL = UUID.randomUUID();
    static final byte[] IMG = {1, 2, 3};

    ReceiptModelClient client = mock(ReceiptModelClient.class);
    CategoryService categories = mock(CategoryService.class);
    BalanceService balances = mock(BalanceService.class);

    @BeforeEach
    void setUp() {
        when(categories.visible()).thenReturn(List.of(
                new CategoryDto(LEBENSMITTEL, "Lebensmittel", true, false),
                new CategoryDto(UUID.randomUUID(), "Haushalt", true, false)));
        when(balances.balance(Konto.BARKASSE)).thenReturn(new BigDecimal("200.00"));
    }

    ReceiptRecognitionService service(boolean enabled) {
        return new ReceiptRecognitionService(client, new ReceiptAiProperties(enabled, "klein", "gross"), categories, balances);
    }

    RawReceipt good() {
        return new RawReceipt("REWE", LocalDate.now().minusDays(1).toString(), new BigDecimal("23.40"), "lebensmittel");
    }

    @Test
    void disabledDoesNotCallModel() {
        assertThat(service(false).recognize(IMG, "image/jpeg")).isEmpty();
        verify(client, never()).extract(any(), any(), any(), any());
    }

    @Test
    void plausibleResultOfSmallModelIsUsedWithOneCall() {
        when(client.extract(any(), any(), any(), eq("klein"))).thenReturn(Optional.of(good()));
        var s = service(true).recognize(IMG, "image/jpeg");
        assertThat(s).isPresent();
        assertThat(s.get().kategorieId()).isEqualTo(LEBENSMITTEL);
        assertThat(s.get().betrag()).isEqualByComparingTo("23.40");
        verify(client, times(1)).extract(any(), any(), any(), any());
    }

    @Test
    void implausibleResultFallsBackToStrongerModel() {
        var broken = new RawReceipt("REWE", LocalDate.now().plusDays(3).toString(), new BigDecimal("23.40"), "Lebensmittel");
        when(client.extract(any(), any(), any(), eq("klein"))).thenReturn(Optional.of(broken));
        when(client.extract(any(), any(), any(), eq("gross"))).thenReturn(Optional.of(good()));
        var s = service(true).recognize(IMG, "image/jpeg");
        assertThat(s).isPresent();
        assertThat(s.get().datum()).isBefore(LocalDate.now());
        verify(client, times(2)).extract(any(), any(), any(), any());
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
    void unknownCategoryAndBadDateBecomeNull() {
        var raw = new RawReceipt("Kiosk", "gestern", new BigDecimal("5.00"), "Ufo");
        var s = ReceiptRecognitionService.map(raw, categories.visible());
        assertThat(s.kategorieId()).isNull();
        assertThat(s.datum()).isNull();
    }
}
