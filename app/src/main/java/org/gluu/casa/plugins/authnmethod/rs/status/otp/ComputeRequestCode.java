/*
 * casa is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2018, Gluu
 */
package org.gluu.casa.plugins.authnmethod.rs.status.otp;

import org.gluu.casa.misc.Utils;

import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.OK;

/**
 * @author jgomer
 */
public enum ComputeRequestCode {
    NO_DISPLAY_NAME,
    INVALID_MODE,
    SUCCESS;

    public Response getResponse(String key, String request) {

        String json;
        Response.Status httpStatus;

        if (equals(SUCCESS)) {
            httpStatus = OK;
            Map<String, Object> map = new LinkedHashMap<>();    //Ensure data can be received in the same order as here
            map.put("key", key);
            map.put("request", request);

            json = Utils.jsonFromObject(map);
        } else {
            httpStatus = BAD_REQUEST;
            json = Utils.jsonFromObject(Collections.singletonMap("code", toString()));
        }

        return Response.status(httpStatus).entity(json).build();

    }

    public Response getResponse() {
        return getResponse(null, null);
    }

}
