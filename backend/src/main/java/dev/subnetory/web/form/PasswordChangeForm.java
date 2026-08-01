package dev.subnetory.web.form;

import jakarta.validation.constraints.NotBlank;

/**
 * Formulaire de changement de mot de passe self-service.
 */
public class PasswordChangeForm {

    @NotBlank(message = "Le mot de passe actuel est obligatoire.")
    private String currentPassword;

    @NotBlank(message = "Le nouveau mot de passe est obligatoire.")
    private String newPassword;

    @NotBlank(message = "La confirmation du nouveau mot de passe est obligatoire.")
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