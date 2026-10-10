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
    private static final int TIMEOUT_SECONDS = 25;
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ChatClient chat;

    public SpringAiReceiptClient(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    @Override
    public Optional<RawReceipt> extract(byte[] image, String mimeType, List<String> categoryNames, String model) {
        String prompt = """
                Du liest einen Kassenbon aus Deutschland. Gib nur die Daten im vorgegebenen JSON-Schema zurück.
                - haendler: Name des Geschäfts
                - datum: Kaufdatum im Format JJJJ-MM-TT
                - gesamtbetrag: Endsumme in Euro als Zahl mit Punkt als Dezimaltrenner
                - kategorie: genau einer dieser Namen: %s
                Wenn du dir bei einem Feld nicht sicher bist, setze es auf null.
                """.formatted(String.join(", ", categoryNames));
        Future<RawReceipt> call = EXECUTOR.submit(() -> chat.prompt()
                .options(OpenAiChatOptions.builder().model(model))
                .user(u -> u.text(prompt).media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(image)))
                .call()
                .entity(RawReceipt.class));
        try {
            return Optional.ofNullable(call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
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
