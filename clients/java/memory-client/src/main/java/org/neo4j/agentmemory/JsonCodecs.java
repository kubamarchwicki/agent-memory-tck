package org.neo4j.agentmemory;

final class JsonCodecs {
    private static final String JACKSON_3_MAPPER = "tools.jackson.databind.json.JsonMapper";
    private static final String JACKSON_3_COORDINATE =
            "tools.jackson.core:jackson-databind:3.1.5";

    private JsonCodecs() {}

    static JsonCodec jackson3() {
        try {
            Class.forName(JACKSON_3_MAPPER, false, JsonCodecs.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError failure) {
            throw missingJackson3(failure);
        }

        try {
            return new Jackson3JsonCodec();
        } catch (LinkageError failure) {
            throw missingJackson3(failure);
        }
    }

    private static MissingJsonCodecException missingJackson3(Throwable cause) {
        return new MissingJsonCodecException(
                "No supported JSON codec is available; add " + JACKSON_3_COORDINATE,
                cause);
    }
}
