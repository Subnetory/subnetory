package dev.subnetory.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerPasswordChangeRequiredTest {

    @Test
    void passwordChangeRequired_returnsForbiddenProblemDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var problem = handler.handlePasswordChangeRequired(
                new PasswordChangeRequiredException());

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getTitle()).isEqualTo("Password Change Required");
        assertThat(problem.getProperties())
                .containsEntry("code", "PASSWORD_CHANGE_REQUIRED");
        assertThat(problem.getDetail())
                .contains("must be changed");
    }
}
