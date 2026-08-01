package dev.subnetory.util;

import dev.subnetory.csv.CsvParseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportFileValidatorTest {

    private final ImportFileValidator validator = new ImportFileValidator(DataSize.ofMegabytes(5));

    @Test
    void acceptsTextCsvWithMatchingExtension() throws Exception {
        var file = new MockMultipartFile("file", "addresses.csv", "text/csv",
                "address,subnet_id\n10.0.0.1,1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(validator.validate(file, "csv")).isNotEmpty();
    }

    @Test
    void rejectsExtensionConfusionAndBinaryCsv() {
        var wrongExtension = new MockMultipartFile("file", "addresses.xlsx", "text/csv",
                "address,subnet_id".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var binary = new MockMultipartFile("file", "addresses.csv", "text/csv",
                new byte[]{'a', 0, 'b'});

        assertThatThrownBy(() -> validator.validate(wrongExtension, "csv"))
                .isInstanceOf(CsvParseException.class);
        assertThatThrownBy(() -> validator.validate(binary, "csv"))
                .isInstanceOf(CsvParseException.class);
    }

    @Test
    void rejectsFakeXlsxWithoutZipSignature() {
        var file = new MockMultipartFile("file", "addresses.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not-a-workbook".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(file, "xlsx"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void limitFollowsConfiguredMaxFileSizeInsteadOfAFixedConstant() throws Exception {
        var oneMbValidator = new ImportFileValidator(DataSize.ofMegabytes(1));
        var content = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(content, (byte) 'a');
        var file = new MockMultipartFile("file", "addresses.csv", "text/csv", content);

        // Accepted under the default 5 MB validator used by the other tests.
        assertThat(validator.validate(file, "csv")).hasSize(content.length);

        // Rejected once the validator is configured with a 1 MB limit,
        // proving the threshold is no longer a hardcoded constant.
        assertThatThrownBy(() -> oneMbValidator.validate(file, "csv"))
                .isInstanceOf(CsvParseException.class);
    }

    @Test
    void csvSafeValueNeutralizesSpreadsheetFormulaPrefixes() {
        assertThat(CsvSafeValue.protect("=HYPERLINK(\"bad\")")).startsWith("'");
        assertThat(CsvSafeValue.protect("  @SUM(1,2)")).startsWith("'");
        assertThat(CsvSafeValue.protect("server-01")).isEqualTo("server-01");
    }

    @Test
    void csvSafeValueNeutralizesTabAndCarriageReturnPrefixes() {
        assertThat(CsvSafeValue.protect("\t=cmd|'/c calc'!A1")).startsWith("'");
        assertThat(CsvSafeValue.protect("\r+cmd|'/c calc'!A1")).startsWith("'");
    }
}
