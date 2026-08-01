package dev.subnetory.csv;

import dev.subnetory.dto.CsvImportResponse.CsvRowError;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parseur XLSX pour l'import d'adresses IP.
 *
 * <h3>Format attendu</h3>
 * <p>Première feuille du classeur, première ligne = header :</p>
 * <pre>
 * address | subnet_id | subnet_network | mac | hostname | description | temporary | discovery_source
 * 192.168.1.10 | 3 |  | aa:bb:cc:dd:ee:ff | srv-web-01 | Serveur web | false | xlsx
 * 192.168.1.20 |  | 192.168.1.0/24 |  | printer-01 | Imprimante |  |
 * </pre>
 *
 * <h3>Règles</h3>
 * <ul>
 *   <li>{@code address} — obligatoire</li>
 *   <li>{@code subnet_id} ou {@code subnet_network} — au moins l'un est obligatoire</li>
 *   <li>Si les deux sont fournis : vérification de cohérence au niveau service</li>
 *   <li>Colonnes optionnelles : {@code mac}, {@code hostname}, {@code description},
 *       {@code temporary} (défaut {@code false}), {@code discovery_source} (défaut {@code xlsx})</li>
 * </ul>
 *
 * <h3>Types POI</h3>
 * <p>Contrairement au CSV, les cellules Excel ont un type natif :</p>
 * <ul>
 *   <li>{@code subnet_id} — cellule NUMERIC (Excel stocke les entiers comme doubles)</li>
 *   <li>{@code temporary} — cellule BOOLEAN ou STRING "true"/"false"</li>
 *   <li>Toutes les autres colonnes — cellule STRING</li>
 * </ul>
 * <p>Ce parser normalise tous les types vers String avant traitement,
 * identiquement au comportement du parseur CSV.</p>
 *
 * <p>Le format de ce fichier est identique au format CSV d'import —
 * un fichier exporté depuis Subnetory peut être réimporté directement.</p>
 *
 * @see AddressCsvParser
 */
@Component
public class AddressXlsxParser {

    private static final Logger log = LoggerFactory.getLogger(AddressXlsxParser.class);

    /** Colonnes obligatoires dans le header. */
    static final List<String> REQUIRED_COLUMNS = List.of("address");

    /**
     * Nombre maximal de lignes de donnees acceptees (correctif securite M1).
     * Au-dela, l'import est refuse pour eviter un epuisement memoire/CPU.
     * Un reseau d'entreprise gere rarement plus de 50 000 adresses par import.
     */
    static final int MAX_DATA_ROWS = 50_000;

    /** Toutes les colonnes reconnues. */
    static final List<String> KNOWN_COLUMNS = List.of(
            "address", "subnet_id", "subnet_network",
            "mac", "hostname", "description", "temporary", "discovery_source"
    );

    /**
     * Parse un flux XLSX et retourne les lignes valides et les erreurs.
     *
     * <p>Seule la première feuille du classeur est lue.
     * La première ligne est considérée comme le header.</p>
     *
     * @param inputStream flux XLSX (format Office Open XML)
     * @return résultat contenant les lignes parsées et les erreurs par ligne
     * @throws CsvParseException si le fichier est vide, illisible,
     *                           ou si le header est invalide
     */
    public AddressCsvParser.ParseResult parse(InputStream inputStream) throws CsvParseException {
        List<AddressCsvParser.CsvRow> rows = new ArrayList<>();
        List<CsvRowError> errors = new ArrayList<>();

        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {

            // getSheetAt(0) lève IllegalArgumentException si le classeur n'a aucune feuille.
            // On vérifie donc getNumberOfSheets() en premier pour produire un message propre.
            if (workbook.getNumberOfSheets() == 0) {
                throw new CsvParseException("XLSX file contains no sheets");
            }
            Sheet sheet = workbook.getSheetAt(0);

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new CsvParseException("XLSX file is empty or has no header row");
            }

            // Lire et normaliser le header (trim + lowercase)
            Map<String, Integer> colIndex = parseHeader(headerRow);
            validateHeader(colIndex);

            // Lire les lignes de données (à partir de la ligne 1, index 1)
            int lastRowIndex = sheet.getLastRowNum();
            int rowNum = 0;

            for (int i = 1; i <= lastRowIndex; i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, colIndex.size())) {
                    continue;
                }

                rowNum++;
                if (rowNum > MAX_DATA_ROWS) {
                    throw new CsvParseException(
                            "XLSX file exceeds the maximum of " + MAX_DATA_ROWS
                            + " data rows. Split the file into smaller imports.");
                }
                try {
                    AddressCsvParser.CsvRow csvRow = parseRow(rowNum, row, colIndex);
                    rows.add(csvRow);
                } catch (CsvRowException e) {
                    errors.add(new CsvRowError(rowNum, e.getAddress(), e.getMessage()));
                    // i+1 = numéro réel dans la feuille Excel (header = ligne 1)
                    log.debug("XLSX row {} (Excel row {}) error: {}", rowNum, i + 1, e.getMessage());
                }
            }

            if (rowNum == 0 && errors.isEmpty()) {
                log.debug("XLSX parsed: no data rows found after header");
            }

        } catch (CsvParseException e) {
            throw e;
        } catch (IOException e) {
            throw new CsvParseException("Failed to read XLSX file: " + e.getMessage());
        } catch (Exception e) {
            // POI peut lever des exceptions non-IOException sur des fichiers corrompus
            throw new CsvParseException("Invalid XLSX file: " + e.getMessage());
        }

        log.info("XLSX parsed: {} valid rows, {} errors", rows.size(), errors.size());
        return new AddressCsvParser.ParseResult(rows, errors);
    }

    // -------------------------------------------------------
    // Lecture et validation du header
    // -------------------------------------------------------

    /**
     * Lit la ligne de header et retourne un index nom_colonne → position.
     * Les noms sont normalisés (trim + lowercase).
     */
    private Map<String, Integer> parseHeader(Row headerRow) {
        Map<String, Integer> colIndex = new HashMap<>();
        int lastCellNum = headerRow.getLastCellNum();

        for (int i = 0; i < lastCellNum; i++) {
            Cell cell = headerRow.getCell(i);
            if (cell == null) continue;

            String name = cellToString(cell);
            if (name == null || name.isBlank()) continue;

            name = name.trim().toLowerCase();
            colIndex.put(name, i);
        }
        return colIndex;
    }

    private void validateHeader(Map<String, Integer> colIndex) throws CsvParseException {
        for (String required : REQUIRED_COLUMNS) {
            if (!colIndex.containsKey(required)) {
                throw new CsvParseException(
                        "Missing required column '" + required + "'. " +
                        "Expected columns: " + String.join(", ", KNOWN_COLUMNS));
            }
        }
        if (!colIndex.containsKey("subnet_id") && !colIndex.containsKey("subnet_network")) {
            throw new CsvParseException(
                    "XLSX must contain at least one of: 'subnet_id', 'subnet_network'");
        }
    }

    // -------------------------------------------------------
    // Parse d'une ligne de données
    // -------------------------------------------------------

    /**
     * Parse une ligne de données Excel en {@link AddressCsvParser.CsvRow}.
     *
     * <p>Le paramètre {@code rowNum} est le numéro séquentiel de ligne de données
     * (1 = première ligne après header, lignes vides exclues), cohérent avec la
     * convention de {@link dev.subnetory.dto.CsvImportResponse.CsvRowError#row()}.
     * Le numéro Excel réel ({@code i+1} dans la boucle d'appel) est tracé en DEBUG
     * uniquement.</p>
     *
     * @param rowNum numéro séquentiel (1 = première ligne de données, hors header)
     */
    private AddressCsvParser.CsvRow parseRow(int rowNum, Row row,
                                              Map<String, Integer> colIndex)
            throws CsvRowException {

        for (Map.Entry<String, Integer> column : colIndex.entrySet()) {
            Cell cell = row.getCell(column.getValue());
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                throw new CsvRowException(rowNum, null,
                        "Excel formulas are not allowed (column '" + column.getKey() + "')");
            }
        }

        String address = getString(row, colIndex, "address");
        if (address == null || address.isBlank()) {
            throw new CsvRowException(rowNum, null,
                    "Column 'address' is required and cannot be empty");
        }
        address = address.trim();

        // subnet_id — cellule NUMERIC dans Excel (double → long)
        String subnetIdStr = getString(row, colIndex, "subnet_id");
        String subnetNetwork = getString(row, colIndex, "subnet_network");

        Long subnetId = null;
        if (subnetIdStr != null && !subnetIdStr.isBlank()) {
            try {
                // getString normalise déjà les NUMERIC en entier (sans ".0")
                subnetId = Long.parseLong(subnetIdStr.trim());
            } catch (NumberFormatException e) {
                throw new CsvRowException(rowNum, address,
                        "Column 'subnet_id' must be a numeric ID, got: '" + subnetIdStr + "'");
            }
        }

        if (subnetId == null && (subnetNetwork == null || subnetNetwork.isBlank())) {
            throw new CsvRowException(rowNum, address,
                    "Either 'subnet_id' or 'subnet_network' is required");
        }

        // Champs optionnels
        String mac         = trimOrNull(getString(row, colIndex, "mac"));
        String hostname    = trimOrNull(getString(row, colIndex, "hostname"));
        String description = trimOrNull(getString(row, colIndex, "description"));

        // temporary — cellule BOOLEAN ou STRING "true"/"false"
        boolean temporary = parseTemporary(rowNum, address, row, colIndex);

        // discovery_source — défaut "xlsx" (différent du CSV qui met "csv")
        String discoverySource = getString(row, colIndex, "discovery_source");
        if (discoverySource == null || discoverySource.isBlank()) {
            discoverySource = "xlsx";
        } else {
            discoverySource = discoverySource.trim().toLowerCase();
        }

        return new AddressCsvParser.CsvRow(
                rowNum,
                address, subnetId,
                subnetNetwork == null || subnetNetwork.isBlank() ? null : subnetNetwork.trim(),
                mac, hostname, description, temporary, discoverySource
        );
    }

    private boolean parseTemporary(int rowNum, String address, Row row,
                                   Map<String, Integer> colIndex) throws CsvRowException {
        Integer idx = colIndex.get("temporary");
        if (idx == null) return false;

        Cell cell = row.getCell(idx);
        if (cell == null || cell.getCellType() == CellType.BLANK) return false;

        // Cellule booléenne native Excel (cochée/décochée)
        if (cell.getCellType() == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }

        // Cellule STRING : mêmes valeurs acceptées que le parser CSV
        String val = cellToString(cell);
        if (val == null || val.isBlank()) return false;

        String t = val.trim().toLowerCase();
        if ("true".equals(t) || "1".equals(t) || "yes".equals(t))  return true;
        if ("false".equals(t) || "0".equals(t) || "no".equals(t))  return false;

        throw new CsvRowException(rowNum, address,
                "Column 'temporary' must be true/false, got: '" + val + "'");
    }

    // -------------------------------------------------------
    // Helpers de lecture de cellule
    // -------------------------------------------------------

    /**
     * Lit une cellule et retourne sa valeur comme String.
     *
     * <p>Gestion des types POI :</p>
     * <ul>
     *   <li>NUMERIC — converti en entier (sans ".0") si la valeur est un entier,
     *       sinon en chaîne décimale. Couvre {@code subnet_id} stocké comme double.</li>
     *   <li>BOOLEAN — "true" ou "false"</li>
     *   <li>STRING — valeur brute</li>
     *   <li>FORMULA — refusée dans les lignes de données</li>
     *   <li>BLANK / null — retourne null</li>
     * </ul>
     */
    private String cellToString(Cell cell) {
        if (cell == null) return null;

        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                // Convertir en entier si la valeur est sans décimale (ex: 42.0 → "42")
                yield (d == Math.floor(d) && !Double.isInfinite(d))
                        ? String.valueOf((long) d)
                        : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> null;
            default -> null;
        };
    }

    /**
     * Lit la valeur d'une colonne nommée dans la ligne courante.
     * Retourne null si la colonne est absente ou la cellule vide.
     */
    private String getString(Row row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null) return null;

        Cell cell = row.getCell(idx);
        String val = cellToString(cell);
        return (val == null || val.isBlank()) ? null : val;
    }

    private String trimOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }

    /**
     * Retourne true si toutes les cellules connues de la ligne sont vides.
     * Les lignes vides intercalées dans le fichier sont ignorées.
     */
    private boolean isBlankRow(Row row, int knownColCount) {
        int lastCell = row.getLastCellNum();
        int limit = Math.max(lastCell, knownColCount);
        for (int i = 0; i < limit; i++) {
            Cell cell = row.getCell(i);
            String val = cellToString(cell);
            if (val != null && !val.isBlank()) return false;
        }
        return true;
    }
}
