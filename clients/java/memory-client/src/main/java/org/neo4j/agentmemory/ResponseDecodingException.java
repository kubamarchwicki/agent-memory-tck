package org.neo4j.agentmemory;

public final class ResponseDecodingException extends MemoryClientException {
    private final String operation;
    private final int statusCode;
    private final String contentType;
    private final String responseBodyExcerpt;

    public ResponseDecodingException(
            String operation,
            int statusCode,
            String contentType,
            String responseBodyExcerpt,
            Throwable cause) {
        super(operation + " could not decode HTTP " + statusCode + " response", cause);
        this.operation = operation;
        this.statusCode = statusCode;
        this.contentType = contentType;
        this.responseBodyExcerpt = responseBodyExcerpt;
    }

    public String operation() {
        return operation;
    }

    public int statusCode() {
        return statusCode;
    }

    public String contentType() {
        return contentType;
    }

    public String responseBodyExcerpt() {
        return responseBodyExcerpt;
    }
}
