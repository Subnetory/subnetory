package dev.subnetory.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sans ces handlers, HttpRequestMethodNotSupportedException (405) et
 * NoHandlerFoundException (404) retombent dans le catch-all Exception.class
 * et sont ecrasees en 500 "unexpected error" — reproduit par un cas reel :
 * un formulaire rendu directement (sans redirect) apres un POST laisse
 * l'URL du navigateur sur un endpoint POST-only ; un lien "returnTo" pointant
 * vers cette URL declenche un GET sans handler.
 */
class GlobalExceptionHandlerRoutingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void methodNotSupported_returns405NotGeneric500() {
        var ex = new HttpRequestMethodNotSupportedException("GET");

        var problem = handler.handleMethodNotSupported(ex);

        assertThat(problem.getStatus()).isEqualTo(405);
        assertThat(problem.getTitle()).isEqualTo("Method Not Allowed");
    }

    @Test
    void noHandlerFound_returns404NotGeneric500() {
        var ex = new NoHandlerFoundException(
                HttpMethod.GET.name(), "/network/addresses/reserve/generate",
                org.springframework.http.HttpHeaders.EMPTY);

        var problem = handler.handleNoHandlerFound(ex);

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Resource Not Found");
    }
}
