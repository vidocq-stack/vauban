package io.vidocq.vauban.example.lib;

import jakarta.enterprise.context.Dependent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A @Dependent-scoped CDI bean — a new instance is created for each injection point.
 */
@Dependent
public class TimeService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public String now() {
        return LocalTime.now().format(FORMATTER);
    }
}
