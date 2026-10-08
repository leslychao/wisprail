package app.wisprail.connection;

import java.time.Instant;
import java.util.UUID;

public record ConnectionEvent(
    long sequence, Instant time, String level, UUID profileId, String message, String detail) {}
