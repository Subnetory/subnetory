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
 * <p>Limite propre ({@code subnetory.import.max-file-size}, audit 03/08/2026,
 * correctif MOYEN) : ce validateur réutilisait auparavant
 * {@code spring.servlet.multipart.max-file-size}, dimensionnée à 200MB pour
 * les dumps de sauvegarde importés en base — un compte {@code ROLE_IP}
 * pouvait donc faire charger en mémoire un XLSX jusqu'à 200MB, que
 * {@link org.apache.poi.xssf.usermodel.XSSFWorkbook} (non-streaming) charge
 * intégralement en mémoire pour le parser. Une limite bien plus basse,
 * indépendante de celle des sauvegardes, suffit largement pour un import
 * d'adresses IP.</p>
 */
@Component
public class ImportFileValidator {

    private final long maxBytes;

    public ImportFileValidator(
            @Value("${subnetory.import.max-file-size:10MB}") DataSize maxFileSize) {
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
