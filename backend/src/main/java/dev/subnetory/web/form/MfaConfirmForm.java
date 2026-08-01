package dev.subnetory.web.form;

import jakarta.validation.constraints.NotBlank;

/** Formulaire d'un unique code MFA (TOTP ou recuperation) : activation, regeneration. */
public class MfaConfirmForm {

    @NotBlank(message = "Le code est obligatoire.")
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
