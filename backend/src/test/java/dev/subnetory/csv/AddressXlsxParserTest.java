package dev.subnetory.csv;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires d'AddressXlsxParser.
 *
 * <p>Tests purs sans Spring ni Testcontainers. Les fichiers XLSX sont construits
 * en mémoire via Apache POI, comme le font les exports existants dans le projet.</p>
 */
class AddressXlsxParserTest {

    private AddressXlsxParser parser;

    @BeforeEach
    void setUp() {
        parser = new AddressXlsxParser();
    }

    // -------------------------------------------------------
    // Helpers de construction de fichiers XLSX en mémoire
    // -------------------------------------------------------

    /** Crée un XLSX avec le header standard et les lignes de données fournies. */
    private InputStream xlsx(String[] header, String[]... dataRows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Adresses");

            // Header
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                headerRow.createCell(i).setCellValue(header[i]);
            }

            // Lignes de données
            for (int r = 0; r < dataRows.length; r++) {
                Row row = sheet.createRow(r + 1);
                String[] data = dataRows[r];
                for (int c = 0; c < data.length; c++) {
                    if (data[c] != null) {
                        row.createCell(c).setCellValue(data[c]);
                    }
                    // null → cellule absente → traitée comme vide par le parser
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    /** Header standard de Subnetory (identique à EXPORT_HEADER). */
    private static final String[] STANDARD_HEADER = {
        "address", "subnet_id", "subnet_network",
        "mac", "hostname", "description", "temporary", "discovery_source"
    };

    // -------------------------------------------------------
    // Cas nominal — parsing correct
    // -------------------------------------------------------

    @Test
    @DisplayName("XLSX valide avec subnet_id — lignes parsées correctement")
    void parse_validXlsx_subnetId_returnsRows() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"192.168.1.10", "3", null, "aa:bb:cc:dd:ee:ff", "srv-web-01", "Serveur web", "false", "xlsx"}
        );

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(1, result.rows().size());
        assertTrue(result.errors().isEmpty());

        AddressCsvParser.CsvRow row = result.rows().get(0);
        assertEquals("192.168.1.10", row.address());
        assertEquals(3L, row.subnetId());
        assertNull(row.subnetNetwork());
        assertEquals("aa:bb:cc:dd:ee:ff", row.mac());
        assertEquals("srv-web-01", row.hostname());
        assertEquals("Serveur web", row.description());
        assertFalse(row.temporary());
        assertEquals("xlsx", row.discoverySource());
    }

    @Test
    @DisplayName("XLSX valide avec subnet_network — subnetId null, network renseigné")
    void parse_validXlsx_subnetNetwork_returnsRow() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.5", null, "10.0.0.0/24", null, "printer-01", null, null, null}
        );

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(1, result.rows().size());
        AddressCsvParser.CsvRow row = result.rows().get(0);
        assertNull(row.subnetId());
        assertEquals("10.0.0.0/24", row.subnetNetwork());
    }

    @Test
    @DisplayName("Plusieurs lignes valides — toutes parsées")
    void parse_multipleRows_allParsed() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"192.168.1.10", "1", null, null, "host-a", null, null, null},
            new String[]{"192.168.1.11", "1", null, null, "host-b", null, null, null},
            new String[]{"192.168.1.12", "1", null, null, "host-c", null, null, null}
        );

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(3, result.rows().size());
        assertTrue(result.errors().isEmpty());
    }

    // -------------------------------------------------------
    // Types POI spécifiques
    // -------------------------------------------------------

    @Test
    @DisplayName("subnet_id cellule NUMERIC — converti en Long correctement")
    void parse_subnetIdNumericCell_parsedAsLong() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("address");
            header.createCell(1).setCellValue("subnet_id");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("10.0.0.1");
            // Cellule NUMERIC — POI stocke comme double
            data.createCell(1).setCellValue(42.0);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);

            AddressCsvParser.ParseResult result =
                parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertEquals(1, result.rows().size());
            assertEquals(42L, result.rows().get(0).subnetId());
        }
    }

    @Test
    @DisplayName("temporary cellule BOOLEAN native Excel — true parsé correctement")
    void parse_temporaryBooleanCell_trueReturnedCorrectly() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("address");
            header.createCell(1).setCellValue("subnet_id");
            header.createCell(2).setCellValue("temporary");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("10.0.0.2");
            data.createCell(1).setCellValue(1.0);
            // Cellule booléenne native (type BOOLEAN, pas STRING)
            data.createCell(2).setCellValue(true);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);

            AddressCsvParser.ParseResult result =
                parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertEquals(1, result.rows().size());
            assertTrue(result.rows().get(0).temporary());
        }
    }

    @Test
    @DisplayName("temporary cellule BOOLEAN native Excel — false parsé correctement")
    void parse_temporaryBooleanCell_falseReturnedCorrectly() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("address");
            header.createCell(1).setCellValue("subnet_id");
            header.createCell(2).setCellValue("temporary");

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("10.0.0.3");
            data.createCell(1).setCellValue(1.0);
            data.createCell(2).setCellValue(false);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);

            AddressCsvParser.ParseResult result =
                parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertEquals(1, result.rows().size());
            assertFalse(result.rows().get(0).temporary());
        }
    }

    @Test
    @DisplayName("temporary STRING 'true' — true parsé correctement")
    void parse_temporaryStringTrue_parsedCorrectly() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.4", "1", null, null, null, null, "true", null}
        );
        AddressCsvParser.ParseResult result = parser.parse(in);
        assertTrue(result.rows().get(0).temporary());
    }

    @Test
    @DisplayName("temporary STRING 'false' — false parsé correctement")
    void parse_temporaryStringFalse_parsedCorrectly() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.5", "1", null, null, null, null, "false", null}
        );
        AddressCsvParser.ParseResult result = parser.parse(in);
        assertFalse(result.rows().get(0).temporary());
    }

    // -------------------------------------------------------
    // Valeurs par défaut
    // -------------------------------------------------------

    @Test
    @DisplayName("discovery_source absent — défaut 'xlsx'")
    void parse_discoverySourceAbsent_defaultsToXlsx() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.6", "1", null, null, null, null, null, null}
        );
        AddressCsvParser.ParseResult result = parser.parse(in);
        assertEquals("xlsx", result.rows().get(0).discoverySource());
    }

    @Test
    @DisplayName("champs optionnels absents — null accepté sans erreur")
    void parse_optionalFieldsAbsent_nullAccepted() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.7", "1", null, null, null, null, null, null}
        );
        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(1, result.rows().size());
        AddressCsvParser.CsvRow row = result.rows().get(0);
        assertNull(row.mac());
        assertNull(row.hostname());
        assertNull(row.description());
        assertFalse(row.temporary());
        assertEquals("xlsx", row.discoverySource());
    }

    // -------------------------------------------------------
    // Lignes vides intercalées
    // -------------------------------------------------------

    @Test
    @DisplayName("lignes vides intercalées — ignorées, rowNum séquentiel sur données")
    void parse_blankRowsIgnored_rowNumSequential() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();

            // Header en ligne 0
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("address");
            header.createCell(1).setCellValue("subnet_id");

            // Ligne de données en ligne 1
            Row data1 = sheet.createRow(1);
            data1.createCell(0).setCellValue("10.0.0.1");
            data1.createCell(1).setCellValue(1.0);

            // Ligne vide en ligne 2 — pas de createRow (null)

            // Ligne de données en ligne 3
            Row data2 = sheet.createRow(3);
            data2.createCell(0).setCellValue("10.0.0.2");
            data2.createCell(1).setCellValue(1.0);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);

            AddressCsvParser.ParseResult result =
                parser.parse(new ByteArrayInputStream(out.toByteArray()));

            // 2 lignes de données, ligne vide ignorée
            assertEquals(2, result.rows().size());
            assertTrue(result.errors().isEmpty());
            // rowNum séquentiel : 1 et 2 (pas 1 et 3)
            assertEquals(1, result.rows().get(0).row());
            assertEquals(2, result.rows().get(1).row());
        }
    }

    // -------------------------------------------------------
    // Erreurs par ligne (ne bloquent pas l'import)
    // -------------------------------------------------------

    @Test
    @DisplayName("colonne address vide — erreur sur la ligne, autres lignes importées")
    void parse_emptyAddress_reportsRowError() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.1", "1", null, null, "valid", null, null, null},
            new String[]{"",          "1", null, null, "bad",   null, null, null},  // address vide
            new String[]{"10.0.0.3", "1", null, null, "valid", null, null, null}
        );

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(2, result.rows().size());
        assertEquals(1, result.errors().size());
        assertEquals(2, result.errors().get(0).row());
        assertNull(result.errors().get(0).address());
        assertTrue(result.errors().get(0).reason().contains("address"));
    }

    @Test
    @DisplayName("subnet_id et subnet_network absents — erreur sur la ligne")
    void parse_noSubnetReference_reportsRowError() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.1", null, null, null, null, null, null, null}
        );

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(0, result.rows().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).reason().contains("subnet_id"));
    }

    @Test
    @DisplayName("subnet_id non numérique — erreur sur la ligne")
    void parse_subnetIdNotNumeric_reportsRowError() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.1", "abc", null, null, null, null, null, null}
        );

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).reason().contains("subnet_id"));
    }

    @Test
    @DisplayName("temporary valeur invalide — erreur sur la ligne")
    void parse_temporaryInvalidValue_reportsRowError() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.1", "1", null, null, null, null, "maybe", null}
        );

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).reason().contains("temporary"));
    }

    @Test
    @DisplayName("import mixte — créations + erreurs → rapport complet cohérent")
    void parse_mixed_correctReport() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER,
            new String[]{"10.0.0.1", "1", null, null, "ok",  null, null, null},
            new String[]{"",          "1", null, null, "bad", null, null, null},  // address vide
            new String[]{"10.0.0.3", "1", null, null, "ok",  null, null, null},
            new String[]{"10.0.0.4", null, null, null, null, null, null, null}    // pas de subnet
        );

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertEquals(2, result.rows().size());
        assertEquals(2, result.errors().size());
    }

    @Test
    @DisplayName("les formules Excel sont refusées et jamais évaluées")
    void parse_formulaCell_isRejected() throws Exception {
        byte[] content;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Adresses");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("address");
            header.createCell(1).setCellValue("subnet_id");
            header.createCell(2).setCellValue("hostname");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("10.0.0.1");
            data.createCell(1).setCellValue("1");
            data.createCell(2).setCellFormula("HYPERLINK(\"https://invalid.local\",\"host\")");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            content = out.toByteArray();
        }

        AddressCsvParser.ParseResult result = parser.parse(new ByteArrayInputStream(content));

        assertTrue(result.rows().isEmpty());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().reason().contains("formulas are not allowed"));
    }

    // -------------------------------------------------------
    // Erreurs fatales (CsvParseException — HTTP 400)
    // -------------------------------------------------------

    @Test
    @DisplayName("stream vide (0 octet) — CsvParseException")
    void parse_emptyStream_throwsCsvParseException() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        assertThrows(CsvParseException.class, () -> parser.parse(in));
    }

    @Test
    @DisplayName("XLSX sans feuille — CsvParseException avec message propre")
    void parse_noSheets_throwsCsvParseException() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // Classeur sans aucune feuille
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);

            CsvParseException ex = assertThrows(CsvParseException.class,
                () -> parser.parse(new ByteArrayInputStream(out.toByteArray())));

            assertTrue(ex.getMessage().contains("no sheets"));
        }
    }

    @Test
    @DisplayName("feuille vide (aucune ligne) — CsvParseException")
    void parse_emptySheet_throwsCsvParseException() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Adresses"); // feuille vide, aucune ligne

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);

            assertThrows(CsvParseException.class,
                () -> parser.parse(new ByteArrayInputStream(out.toByteArray())));
        }
    }

    @Test
    @DisplayName("header sans colonne 'address' — CsvParseException")
    void parse_headerMissingAddressColumn_throwsCsvParseException() throws Exception {
        InputStream in = xlsx(
            new String[]{"subnet_id", "hostname"},  // pas de 'address'
            new String[]{"1", "host-a"}
        );

        CsvParseException ex = assertThrows(CsvParseException.class,
            () -> parser.parse(in));

        assertTrue(ex.getMessage().contains("address"));
    }

    @Test
    @DisplayName("header sans subnet_id ni subnet_network — CsvParseException")
    void parse_headerMissingSubnetColumns_throwsCsvParseException() throws Exception {
        InputStream in = xlsx(
            new String[]{"address", "hostname"},    // ni subnet_id ni subnet_network
            new String[]{"10.0.0.1", "host-a"}
        );

        CsvParseException ex = assertThrows(CsvParseException.class,
            () -> parser.parse(in));

        assertTrue(ex.getMessage().contains("subnet_id"));
    }

    @Test
    @DisplayName("seul le header, aucune ligne de données — ParseResult vide sans erreur")
    void parse_headerOnly_emptyResultNoError() throws Exception {
        InputStream in = xlsx(STANDARD_HEADER /* aucune ligne de données */);

        AddressCsvParser.ParseResult result = parser.parse(in);

        assertTrue(result.rows().isEmpty());
        assertTrue(result.errors().isEmpty());
    }
}
