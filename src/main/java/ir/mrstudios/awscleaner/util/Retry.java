package ir.mrstudios.awscleaner.util;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;

public final class Retry {
    private Retry() {}

    public static void pause(int baseSeconds, int retry, String retryAfter) throws InterruptedException {
        long baseMillis = Math.min(60_000, baseSeconds * 1000L * (1L << Math.min(retry, 10)));
        long delay = Math.max(baseMillis + ThreadLocalRandom.current().nextLong(baseMillis / 4 + 1),
                retryAfterMillis(retryAfter));
        Thread.sleep(Math.min(60_000, delay));
    }

    private static long retryAfterMillis(String value) {
        if (value == null) return 0;
        try {
            return Math.max(0, Math.min(60, Long.parseLong(value))) * 1000;
        } catch (NumberFormatException ignored) {
            try {
                return Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis();
            } catch (DateTimeParseException e) {
                return 0;
            }
        }
    }
}
