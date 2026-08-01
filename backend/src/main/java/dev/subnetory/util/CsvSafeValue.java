package dev.subnetory.util;

/** Neutralise les valeurs interprétables comme formules par les tableurs. */
public final class CsvSafeValue {

    private CsvSafeValue() {
    }

    public static String protect(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;

        // OWASP CSV Injection : un caractère de tabulation ou de retour chariot
        // en tête de valeur est lui-même considéré comme dangereux par certains
        // tableurs, indépendamment de ce qui suit.
        char firstChar = value.charAt(0);
        if (firstChar == '\t' || firstChar == '\r') return "'" + value;

        int first = 0;
        while (first < value.length() && Character.isWhitespace(value.charAt(first))) first++;
        if (first < value.length()) {
            char c = value.charAt(first);
            if (c == '=' || c == '+' || c == '-' || c == '@') return "'" + value;
        }
        return value;
    }

    public static String[] protectAll(String[] values) {
        String[] protectedValues = new String[values.length];
        for (int i = 0; i < values.length; i++) protectedValues[i] = protect(values[i]);
        return protectedValues;
    }
}
