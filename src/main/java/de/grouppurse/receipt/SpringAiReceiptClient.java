package de.grouppurse.receipt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class SpringAiReceiptClient implements ReceiptModelClient {
    private static final Logger log = LoggerFactory.getLogger(SpringAiReceiptClient.class);
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatClient chat;
    private final ReceiptAiProperties props;

    public SpringAiReceiptClient(ChatClient.Builder builder, ReceiptAiProperties props) {
        this.chat = builder.build();
        this.props = props;
    }

    @Override
    public Optional<RawReceipt> extract(List<ReceiptImage> images, List<String> categoryNames, String model) {
        String prompt = """
                Du liest Kassenbons aus Deutschland. Die Fotos können einen Bon in mehreren Teilen zeigen oder mehrere verschiedene Bons
                nebeneinander, auch überlappend. Gib nur die Daten im vorgegebenen JSON-Schema zurück.
                Gehe so vor:
                1. Zähle die verschiedenen Bons (belegAnzahl). Ein langer Bon, der über mehrere Fotos geht, zählt einmal.
                2. Lies für jeden Bon den Endbetrag. gesamtbetrag ist die Summe der Endbeträge ALLER Bons, nicht nur eines Bons.
                3. positionen: Ordne jede Zeile aller Bons genau einer der erlaubten Kategorien zu und gib je Kategorie die Summe aller
                   dazugehörigen Zeilen über alle Bons an. Rabatte und Pfand rechnest du bei der jeweiligen Kategorie mit ein. Die Summe
                   aller Positionen muss dem gesamtbetrag entsprechen. Lebensmittel gehören in die passende Lebensmittel-Unterkategorie,
                   Drogerie- und Haushaltsartikel in ihre eigenen Kategorien.
                4. haendler: Name des Geschäfts, bei mehreren Bons alle Namen kommagetrennt. datum: Kaufdatum JJJJ-MM-TT (bei mehreren
                   Bons das späteste).
                Setze sicher=false und nenne kurz den Grund in unsicherheit, sobald du dir bei einem Bon nicht sicher bist: Endbetrag
                oder Zeilen sind abgeschnitten, verdeckt, unscharf oder überlappt, ein Bon ist nur teilweise zu sehen, die Zeilen ergeben
                nicht den Endbetrag oder du musst Beträge raten. Rate niemals. Lieber unsicher als falsch.
                Erlaubte Kategorien: %s
                Wenn du dir bei einem einzelnen Feld nicht sicher bist, setze es auf null.
                """.formatted(String.join(", ", categoryNames));
        Future<RawReceipt> call = EXECUTOR.submit(() -> chat.prompt()
                .options(OpenAiChatOptions.builder().model(model))
                .user(u -> {
                    u.text(prompt);
                    for (ReceiptImage image : images) {
                        u.media(MimeTypeUtils.parseMimeType(image.mimeType()), new ByteArrayResource(image.bytes()));
                    }
                })
                .call()
                .entity(RawReceipt.class));
        try {
            return Optional.ofNullable(call.get(props.timeoutSeconds() > 0 ? props.timeoutSeconds() : 180, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return Optional.empty();
        } catch (Exception e) {
            call.cancel(true);
            log.warn("Belegerkennung mit {} fehlgeschlagen: {}", model, e.toString());
            return Optional.empty();
        }
    }
}
