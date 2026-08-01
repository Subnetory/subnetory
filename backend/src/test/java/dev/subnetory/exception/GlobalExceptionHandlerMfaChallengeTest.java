package dev.subnetory.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerMfaChallengeTest {

    @Test
    void mfaRequired_returnsUnauthorizedProblemDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var problem = handler.handleMfaRequired(new MfaRequiredException());

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getTitle()).isEqualTo("MFA Required");
        assertThat(problem.getProperties()).containsEntry("code", "MFA_REQUIRED");
    }

    @Test
    void mfaChallengeFailed_returnsUnauthorizedProblemDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var problem = handler.handleMfaChallengeFailed(new MfaChallengeFailedException());

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getTitle()).isEqualTo("Invalid MFA Code");
        assertThat(problem.getProperties()).containsEntry("code", "MFA_INVALID");
    }
}
