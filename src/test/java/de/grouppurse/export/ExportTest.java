package de.grouppurse.export;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Konto;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.expense.ExpenseDtos.ExpenseInput;
import de.grouppurse.expense.ExpenseDtos.PositionInput;
import de.grouppurse.expense.ExpenseService;
import de.grouppurse.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExportTest extends AbstractIntegrationTest {
    @Autowired LedgerService ledger;
    @Autowired ExpenseService expenses;
    @Autowired CategoryService categories;

    Wohngruppe g;
    Person kv;
    MockHttpSession kvSession;
    final int year = LocalDate.now().getYear();
    static final byte[] FOTO1 = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    static final byte[] FOTO2 = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 4, 5, 6};

    @BeforeEach
    void setUp() throws Exception {
        g = t.group("Sonnenhof Süd");
        kv = t.person("kv", false);
        t.member(kv, g, de.grouppurse.domain.Rolle.KASSENVERWALTER, de.grouppurse.domain.MitgliedStatus.AKTIV);
        kvSession = t.sessionFor(kv, g);
        t.as(kv, g, () -> {
            ledger.anfangsbestand(Konto.BARKASSE, new BigDecimal("200.00"), LocalDate.now());
            UUID cat = categories.leaves().get(0).id();
            expenses.submit(ExpenseInput.single(new BigDecimal("12.50"), LocalDate.now(), cat, "Kiosk \"Eck\"; Berlin", "=SUM(1)", null, null),
                    List.of(new MockMultipartFile("photos", "a.jpg", "image/jpeg", FOTO1),
                            new MockMultipartFile("photos", "b.jpg", "image/jpeg", FOTO2)));
        });
    }

    @Test
    void csvHasBomGermanNumbersAndNeutralisedFormulas() throws Exception {
        byte[] body = mvc.perform(get("/api/export/bookings.csv?jahr=" + year).session(kvSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Gruppenkasse_Sonnenhof_S_d_" + year + ".csv")))
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(body, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Nummer;Datum;Konto;Art;Betrag;");
        assertThat(csv).contains(";-12,50;");
        assertThat(csv).contains("\"'=SUM(1)\"");
        assertThat(csv).contains("\"Kiosk \"\"Eck\"\"; Berlin\"");
        assertThat(csv).contains("ANFANGSBESTAND;200,00;");
    }

    @Test
    void zipContainsPhotosNamedByDateAndNumber() throws Exception {
        byte[] body = mvc.perform(get("/api/export/receipts.zip?jahr=" + year).session(kvSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> names = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                names.add(e.getName());
                contents.add(in.readAllBytes());
            }
        }
        assertThat(names).hasSize(2);
        assertThat(names).allMatch(n -> n.matches("\\d{4}-\\d{2}-\\d{2}_\\d{5}_[12]\\.jpg"));
        assertThat(contents).anySatisfy(c -> assertThat(c).isEqualTo(FOTO1));
        assertThat(contents).anySatisfy(c -> assertThat(c).isEqualTo(FOTO2));
    }

    @Test
    void pdfIsValidAndMentionsGroupAndBalances() throws Exception {
        byte[] body = mvc.perform(get("/api/export/annual-report.pdf?jahr=" + year).session(kvSession))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(body);
        StringBuilder text = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            text.append(extractor.getTextFromPage(p)).append('\n');
        }
        assertThat(text.toString()).contains("Jahresabschluss " + year).contains("Sonnenhof Süd")
                .contains("Endbestand").contains("187,50 €");
    }

    @Test
    void caregiversAndOtherGroupsGetNothing() throws Exception {
        MockHttpSession pk = t.pflegekraft(g);
        for (String url : List.of("/api/export/bookings.csv", "/api/export/receipts.zip", "/api/export/annual-report.pdf")) {
            mvc.perform(get(url + "?jahr=" + year).session(pk)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/export/bookings.csv?jahr=1999").session(kvSession)).andExpect(status().isBadRequest());
        MockHttpSession other = t.kassenverwalter(t.group("Andere"));
        String csv = new String(mvc.perform(get("/api/export/bookings.csv?jahr=" + year).session(other))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("SUM(1)");
    }


    @Test
    void csvAndPdfListEveryPositionOfASplitReceipt() throws Exception {
        UUID obst = t.as(kv, g, () -> categories.leaves().stream().filter(c -> c.name().equals("Obst und Gemüse")).findFirst().orElseThrow().id());
        UUID haushalt = t.as(kv, g, () -> categories.leaves().stream().filter(c -> c.name().equals("Haushalt")).findFirst().orElseThrow().id());
        t.as(kv, g, () -> expenses.submit(new ExpenseInput(LocalDate.now(), List.of(new PositionInput(obst, new BigDecimal("8.40")), new PositionInput(haushalt, new BigDecimal("4.99"))),
                "REWE", "Wocheneinkauf", null, null), List.of(new MockMultipartFile("photos", "a.jpg", "image/jpeg", FOTO1))));
        String csv = new String(mvc.perform(get("/api/export/bookings.csv?jahr=" + year).session(kvSession)).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Lebensmittel › Obst und Gemüse (8,40) | Haushalt (4,99)");
        byte[] pdf = mvc.perform(get("/api/export/annual-report.pdf?jahr=" + year).session(kvSession)).andReturn().getResponse().getContentAsByteArray();
        PdfReader reader = new PdfReader(pdf);
        StringBuilder text = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            text.append(extractor.getTextFromPage(p)).append('\n');
        }
        assertThat(text.toString()).contains("Lebensmittel").contains("Obst und Gemüse").contains("Haushalt");
    }
}
