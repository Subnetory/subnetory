package dev.subnetory.csv;

/**
 * Exception levée pour une ligne CSV invalide.
 * La ligne est ignorée et son erreur est reportée dans la réponse.
 */
public class CsvRowException extends Exception {

    private final int row;
    private final String address;

    public CsvRowException(int row, String address, String message) {
        super(message);
        this.row = row;
        this.address = address;
    }

    public int getRow() { return row; }
    public String getAddress() { return address; }
}
