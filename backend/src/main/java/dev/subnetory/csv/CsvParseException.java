package dev.subnetory.csv;

/**
 * Exception levée quand le fichier CSV lui-même est invalide
 * (vide, header absent, colonnes obligatoires manquantes).
 * Résulte en une réponse HTTP 400.
 */
public class CsvParseException extends Exception {
    public CsvParseException(String message) {
        super(message);
    }
}
