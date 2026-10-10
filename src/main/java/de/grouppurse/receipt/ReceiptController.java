package de.grouppurse.receipt;

import de.grouppurse.expense.ReceiptStorage;
import de.grouppurse.security.Access;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {
    private final Access access;
    private final ReceiptRecognitionService recognition;

    public ReceiptController(Access access, ReceiptRecognitionService recognition) {
        this.access = access;
        this.recognition = recognition;
    }

    /** 204, wenn die Erkennung aus ist oder nichts lieferte; die Pflegekraft füllt die Felder dann selbst aus. */
    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptSuggestion> recognize(@RequestPart("photo") MultipartFile photo) {
        access.requireActiveMember();
        if (!recognition.enabled() || photo.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        if (photo.getSize() > 5L * 1024 * 1024) {
            throw new IllegalArgumentException("Foto ist größer als 5 MB");
        }
        try {
            byte[] bytes = photo.getBytes();
            String ext = ReceiptStorage.detectExtension(bytes);
            String mime = "jpg".equals(ext) ? "image/jpeg" : "png".equals(ext) ? "image/png" : "webp".equals(ext) ? "image/webp" : null;
            if (mime == null) {
                throw new IllegalArgumentException("Nur Fotos (JPEG, PNG, WebP) sind erlaubt");
            }
            return recognition.recognize(bytes, mime).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
