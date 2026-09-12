package dev.paperviz.ingestion;

/**
 * A rejection the user can act on — wrong file type, too large, not open access.
 * Mapped to HTTP 422 with the message shown verbatim in the UI, so messages
 * should say what to do next rather than only what went wrong.
 */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
