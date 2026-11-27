package ir.mrstudios.awscleaner.util;

public final class Ipv4 {
    private Ipv4() {}

    public static boolean valid(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        for (String octet : value.split("\\.")) {
            if (Integer.parseInt(octet) > 255) return false;
        }
        return !value.equals("0.0.0.0");
    }
}
