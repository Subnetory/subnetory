package dev.subnetory.exception;

/**
 * Parametres invalides pour une reservation d'adresses en serie (IP de depart,
 * quantite, nommage...). Distincte de {@link ConflictException} : aucune
 * adresse n'est en cause, c'est la demande elle-meme qui est incorrecte
 * (a traduire en 400 cote API / en message de formulaire cote web).
 */
public class InvalidSeriesException extends RuntimeException {

    public InvalidSeriesException(String message) {
        super(message);
    }
}
