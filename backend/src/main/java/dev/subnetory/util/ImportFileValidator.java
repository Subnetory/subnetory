package dev.subnetory.util;

import dev.subnetory.csv.CsvParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

/**
 * Validation défensive des fichiers d'import avant leur passage aux parseurs.
 *
 * <p>La taille maximale acceptée suit la même propriété que le multipart Spring
 * ({@code spring.servlet.multipart.max-file-size}) afin de conserver une source
 * unique de vérité : changer cette propriété change aussi bien la limite Spring
 * que la limite applicative de ce validateur.</p>
 */
@Component
public class ImportFileValidator {

    private final long maxBytes;

    public ImportFileValidator(
            @Value("${spring.servlet.multipart.max-file-size:5MB}") DataSize maxFileSize) {
        this.maxBytes = maxFileSize.toBytes();
    }

    public byte[] validate(MultipartFile file, String format)
            throws CsvParseException, IOException {
        if (file == null || file.isEmpty()) {
            throw new CsvParseException("Uploaded file is empty");
        }
        if (file.getSize() > maxBytes) {
            throw new CsvParseException("File exceeds the " + maxBytes / (1024 * 1024) + " MB limit");
        }

        String safeFormat = format == null ? "" : format.toLowerCase(Locale.ROOT);
        String filename = file.getOriginalFilename();
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String expectedExtension = "." + safeFormat;
        if (!("csv".equals(safeFormat) || "xlsx".equals(safeFormat))
                || !lowerName.endsWith(expectedExtension)) {
            throw new CsvParseException("File extension does not match the selected format");
        }

        byte[] content = file.getBytes();
        if (content.length == 0 || content.length > maxBytes) {
            throw new CsvParseException("Invalid file size");
        }

        if ("xlsx".equals(safeFormat)) {
            if (content.length < 4 || content[0] != 'P' || content[1] != 'K'
                    || content[2] != 3 || content[3] != 4) {
                throw new CsvParseException("Invalid XLSX file signature");
            }
        } else {
            for (byte value : content) {
                if (value == 0) {
                    throw new CsvParseException("CSV file contains binary data");
                }
            }
            if (content.length >= 2 && content[0] == 'P' && content[1] == 'K') {
                throw new CsvParseException("CSV file contains an unexpected archive");
            }
        }
        return content;
    }
}
