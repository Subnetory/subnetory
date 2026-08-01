package dev.subnetory.csv;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import dev.subnetory.dto.CsvImportResponse.CsvRowError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Parseur CSV pour l'import d'adresses IP.
 *
 * <h3>Format attendu</h3>
 * <pre>
 * address,subnet_id,subnet_network,mac,hostname,description,temporary,discovery_source
 * 192.168.1.10,3,,aa:bb:cc:dd:ee:ff,srv-web-01,Serveur web,false,csv
 * 192.168.1.20,,192.168.1.0/24,,printer-01,Imprimante,,
 * </pre>
 *
 * <h3>Règles</h3>
 * <ul>
 *   <li>{@code address} — obligatoire</li>
 *   <li>{@code subnet_id} ou {@code subnet_network} — au moins l'un est obligatoire</li>
 *   <li>Si les deux sont fournis : vérification de cohérence au niveau service</li>
 *   <li>Colonnes optionnelles : {@code mac}, {@code hostname}, {@code description},
 *       {@code temporary} (défaut {@code false}), {@code discovery_source} (défaut {@code csv})</li>
 * </ul>
 */
@Component
public class AddressCsvParser {

    private static final Logger log = LoggerFactory.getLogger(AddressCsvParser.class);

    /** Colonnes obligatoires dans le header. */
    static final List<String> REQUIRED_COLUMNS = List.of("address");

    /**
     * Nombre maximal de lignes de donnees acceptees (correctif securite M1).
     * Au-dela, l'import est refuse pour eviter un epuisement memoire/CPU.
     */
    static final int MAX_DATA_ROWS = 50_000;

    /** Toutes les colonnes reconnues. */
    static final List<String> KNOWN_COLUMNS = List.of(
            "address", "subnet_id", "subnet_network",
            "mac", "hostname", "description", "temporary", "discovery_source"
    );

    /**
     * Parse un flux CSV et retourne les lignes valides et les erreurs.
     *
     * @param inputStream flux CSV (UTF-8)
     * @return résultat contenant les lignes parsées et les erreurs par ligne
     * @throws CsvParseException si le fichier est vide ou si le header est invalide
     */
    public ParseResult parse(InputStream inputStream) throws CsvParseException {
        List<CsvRow> rows = new ArrayList<>();
        List<CsvRowError> errors = new ArrayList<>();

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String[] header = reader.readNext();
            if (header == null || header.length == 0) {
                throw new CsvParseException("CSV file is empty or has no header row");
            }

            // Normaliser le header (trim + lowercase).
            // Suppression du BOM UTF-8 (\uFEFF) sur la première colonne :
            // les fichiers CSV exportés depuis Excel contiennent souvent ce marqueur,
            // ce qui transforme "address" en "\uFEFFaddress" et cause une erreur
            // "Missing required column 'address'" trompeuse.
            List<String> columns = Arrays.stream(header)
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            // Supprimer le BOM sur la première colonne uniquement
            if (!columns.isEmpty()) {
                columns.set(0, columns.get(0).replace("﻿", ""));
            }

            validateHeader(columns);

            // Construire un index colonne → position
            Map<String, Integer> colIndex = IntStream.range(0, columns.size())
                    .boxed()
                    .collect(java.util.stream.Collectors.toMap(columns::get, i -> i));

            String[] line;
            int rowNum = 0;
            while ((line = reader.readNext()) != null) {
                rowNum++;
                if (rowNum > MAX_DATA_ROWS) {
                    throw new CsvParseException(
                            "CSV file exceeds the maximum of " + MAX_DATA_ROWS
                            + " data rows. Split the file into smaller imports.");
                }
                if (isBlankLine(line)) continue;

                try {
                    CsvRow row = parseLine(rowNum, line, columns, colIndex);
                    rows.add(row);
                } catch (CsvRowException e) {
                    errors.add(new CsvRowError(rowNum, e.getAddress(), e.getMessage()));
                    log.debug("CSV row {} error: {}", rowNum, e.getMessage());
                }
            }

        } catch (CsvParseException e) {
            throw e;
        } catch (CsvValidationException | IOException e) {
            throw new CsvParseException("Failed to read CSV file: " + e.getMessage());
        }

        log.info("CSV parsed: {} valid rows, {} errors", rows.size(), errors.size());
        return new ParseResult(rows, errors);
    }

    // -------------------------------------------------------
    // Validation header
    // -------------------------------------------------------

    private void validateHeader(List<String> columns) throws CsvParseException {
        for (String required : REQUIRED_COLUMNS) {
            if (!columns.contains(required)) {
                throw new CsvParseException(
                        "Missing required column '" + required + "'. " +
                        "Expected columns: " + String.join(", ", KNOWN_COLUMNS));
            }
        }
        // Vérifier qu'au moins subnet_id ou subnet_network est présent dans le header
        if (!columns.contains("subnet_id") && !columns.contains("subnet_network")) {
            throw new CsvParseException(
                    "CSV must contain at least one of: 'subnet_id', 'subnet_network'");
        }
    }

    // -------------------------------------------------------
    // Parse d'une ligne
    // -------------------------------------------------------

    private CsvRow parseLine(int rowNum, String[] line, List<String> columns,
                             Map<String, Integer> colIndex) throws CsvRowException {

        String address = get(line, colIndex, "address");
        if (address == null || address.isBlank()) {
            throw new CsvRowException(rowNum, null, "Column 'address' is required and cannot be empty");
        }
        address = address.trim();

        // subnet_id et/ou subnet_network
        String subnetIdStr = get(line, colIndex, "subnet_id");
        String subnetNetwork = get(line, colIndex, "subnet_network");

        Long subnetId = null;
        if (subnetIdStr != null && !subnetIdStr.isBlank()) {
            try {
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
        String mac = trimOrNull(get(line, colIndex, "mac"));
        String hostname = trimOrNull(get(line, colIndex, "hostname"));
        String description = trimOrNull(get(line, colIndex, "description"));

        boolean temporary = false;
        String temporaryStr = get(line, colIndex, "temporary");
        if (temporaryStr != null && !temporaryStr.isBlank()) {
            String t = temporaryStr.trim().toLowerCase();
            if ("true".equals(t) || "1".equals(t) || "yes".equals(t)) {
                temporary = true;
            } else if (!"false".equals(t) && !"0".equals(t) && !"no".equals(t)) {
                throw new CsvRowException(rowNum, address,
                        "Column 'temporary' must be true/false, got: '" + temporaryStr + "'");
            }
        }

        String discoverySource = get(line, colIndex, "discovery_source");
        if (discoverySource == null || discoverySource.isBlank()) {
            discoverySource = "csv";
        } else {
            discoverySource = discoverySource.trim().toLowerCase();
        }

        return new CsvRow(
                rowNum,
                address, subnetId,
                subnetNetwork == null || subnetNetwork.isBlank() ? null : subnetNetwork.trim(),
                mac, hostname, description, temporary, discoverySource
        );
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private String get(String[] line, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= line.length) return null;
        String val = line[idx];
        return val == null || val.isBlank() ? null : val;
    }

    private String trimOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }

    private boolean isBlankLine(String[] line) {
        return line == null || Arrays.stream(line).allMatch(
                c -> c == null || c.isBlank());
    }

    // -------------------------------------------------------
    // Types internes
    // -------------------------------------------------------

    /**
     * Ligne CSV validée et parsée.
     * @param row numéro de ligne dans le fichier (1 = première ligne de données)
     */
    public record CsvRow(
            int row,
            String address,
            Long subnetId,
            String subnetNetwork,
            String mac,
            String hostname,
            String description,
            boolean temporary,
            String discoverySource
    ) {}

    /** Résultat du parsing : lignes valides + erreurs. */
    public record ParseResult(List<CsvRow> rows, List<CsvRowError> errors) {}
}
