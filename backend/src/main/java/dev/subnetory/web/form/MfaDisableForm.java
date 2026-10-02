package dev.subnetory.web.form;

import jakarta.validation.constraints.NotBlank;

/** Formulaire de desactivation MFA self-service : mot de passe (comptes locaux) + code. */
public class MfaDisableForm {

    /** Obligatoire pour un compte local (verifie par le service) ; ignore pour un compte LDAP. */
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
