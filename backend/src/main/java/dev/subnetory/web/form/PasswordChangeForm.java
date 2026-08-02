package dev.subnetory.web.form;

import jakarta.validation.constraints.NotBlank;

/**
 * Formulaire de changement de mot de passe self-service.
 */
public class PasswordChangeForm {

    @NotBlank(message = "{validation.mfa.currentPasswordRequired}")
    private String currentPassword;

    @NotBlank(message = "{validation.password.newRequired}")
    private String newPassword;

    @NotBlank(message = "{validation.password.confirmRequired}")
    private String confirmPassword;

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}