package dev.kryonix.nimbus.sdk;

/**
 * Thrown when the Nimbus API returns an error response (HTTP 4xx/5xx).
 */
public class NimbusApiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public NimbusApiException(int statusCode, String responseBody) {
        super("Nimbus API error " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
}
