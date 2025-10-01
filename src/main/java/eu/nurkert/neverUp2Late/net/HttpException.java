package eu.nurkert.neverUp2Late.net;

import java.io.IOException;

/**
 * Exception thrown when an HTTP request returns a non-successful status code.
 */
public class HttpException extends IOException {

    private final String url;
    private final int statusCode;
    private final String responseBody;

    public HttpException(String url, int statusCode, String responseBody) {
        super("Request to " + url + " failed with status code " + statusCode);
        this.url = url;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public String getUrl() {
        return url;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
