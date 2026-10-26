package it.projectsrl.standpos.printagent.service;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The agent's one JSON mapper. Unknown properties are ignored so that a newer server can add fields. */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {}
}
