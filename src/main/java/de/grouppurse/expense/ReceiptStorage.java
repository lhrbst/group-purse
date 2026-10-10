package de.grouppurse.expense;

import de.grouppurse.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

/** Belegfotos auf dem Dateisystem, je Wohngruppe ein Ordner. Ausgeliefert wird nur über die API. */
@Component
public class ReceiptStorage {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Pattern SAFE_NAME = Pattern.compile("[0-9a-f-]{36}\\.(jpg|png|webp)");

    private final Path root;

    public ReceiptStorage(@Value("${app.storage.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) {
        try {
            if (file.getSize() > MAX_BYTES) {
                throw new IllegalArgumentException("Foto ist größer als 5 MB");
            }
            byte[] bytes = file.getBytes();
            String ext = detectExtension(bytes);
            if (ext == null) {
                throw new IllegalArgumentException("Nur Fotos (JPEG, PNG, WebP) sind erlaubt");
            }
            UUID tenant = TenantContext.get().orElseThrow(() -> new IllegalStateException("Keine Wohngruppe aktiv"));
            Path dir = root.resolve(tenant.toString());
            Files.createDirectories(dir);
            String name = UUID.randomUUID() + "." + ext;
            Files.write(dir.resolve(name), bytes);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Resource load(String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Ungültiger Dateiname");
        }
        UUID tenant = TenantContext.get().orElseThrow(() -> new IllegalStateException("Keine Wohngruppe aktiv"));
        Path p = root.resolve(tenant.toString()).resolve(name).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p)) {
            return null;
        }
        return new FileSystemResource(p);
    }

    public static String contentType(String name) {
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /** Erkennung am Dateikopf statt am vom Client gemeldeten Typ. */
    public static String detectExtension(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
