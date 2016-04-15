/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.protocol.oidc.endpoints;

import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.NotFoundException;
import org.keycloak.Config;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.common.util.StreamUtil;
import org.keycloak.common.util.UriUtils;
import org.keycloak.saml.common.util.StringUtil;
import org.keycloak.services.validation.Validation;
import org.keycloak.theme.Theme;
import org.keycloak.theme.ThemeProvider;

import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class LoginStatusIframeEndpoint {
    private static final Logger logger = Logger.getLogger(LoginStatusIframeEndpoint.class);

    @Context
    private UriInfo uriInfo;

    @Context
    protected KeycloakSession session;

    private RealmModel realm;

    public LoginStatusIframeEndpoint(RealmModel realm) {
        this.realm = realm;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response getLoginStatusIframe(@QueryParam("client_id") String client_id,
                                         @QueryParam("origin") String origin) {
        if (client_id == null || origin == null) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        if (!UriUtils.isOrigin(origin)) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        ClientModel client = realm.getClientByClientId(client_id);
        if (client == null) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        InputStream is = getClass().getClassLoader().getResourceAsStream("login-status-iframe.html");
        if (is == null) throw new NotFoundException("Could not find login-status-iframe.html ");

        boolean valid = false;
        for (String o : client.getWebOrigins()) {
            if (o.equals("*") || o.equals(origin)) {
                valid = true;
                break;
            }
        }

        for (String r : RedirectUtils.resolveValidRedirects(uriInfo, client.getRootUrl(), client.getRedirectUris())) {
            int i = r.indexOf('/', 8);
            if (i != -1) {
                r = r.substring(0, i);
            }

            if (r.equals(origin)) {
                valid = true;
                break;
            }
        }

        if (!valid) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        ThemeProvider themeProvider = session.getProvider(ThemeProvider.class, "extending");
        Theme theme;
        try {
            theme = themeProvider.getTheme(realm.getLoginTheme(), Theme.Type.LOGIN);
        } catch (IOException e) {
            logger.error("Failed to create theme", e);
            return Response.serverError().build();
        }

        try {
            String file = StreamUtil.readString(is);
            file = file.replace("ORIGIN", origin);

            Response.ResponseBuilder response = Response.ok(file);

            String p3pValue = theme.getProperties().getProperty("sessionIframeP3P");
            if (!Validation.isBlank(p3pValue)) {
                // This header is required by IE, see KEYCLOAK-2828 for details.
                response.header("P3P", p3pValue);
            }

            CacheControl cacheControl = new CacheControl();
            cacheControl.setNoTransform(false);
            cacheControl.setMaxAge(Config.scope("theme").getInt("staticMaxAge", -1));

            return response.cacheControl(cacheControl).build();
        } catch (IOException e) {
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }
    }

}
