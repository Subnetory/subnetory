package dev.subnetory.web.form;

import jakarta.validation.constraints.NotBlank;

/** Formulaire de desactivation MFA self-service : mot de passe + code. */
public class MfaDisableForm {

    @NotBlank(message = "{validation.mfa.currentPasswordRequired}")
    private String currentPassword;

    @NotBlank(message = "{validation.mfa.codeRequired}")
    private String code;

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
