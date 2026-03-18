package com.esmp.incremental.bulk;

public class BulkUtil02 {
    private BulkUtil02() {}

    public static String format(String input) {
        if (input == null) return "";
        return input.trim().toLowerCase();
    }

    public static boolean isValid(String value) {
        return value != null && !value.isBlank();
    }
}
