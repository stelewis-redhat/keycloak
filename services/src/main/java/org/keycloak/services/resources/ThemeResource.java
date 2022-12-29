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
package org.keycloak.services.resources;

import org.jboss.logging.Logger;
import org.keycloak.common.Version;
import org.keycloak.common.util.MimeTypeUtil;
import org.keycloak.encoding.ResourceEncodingHelper;
import org.keycloak.encoding.ResourceEncodingProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.resources.account.AccountRestService;
import org.keycloak.services.util.CacheControlUtil;
import org.keycloak.theme.Theme;
import org.keycloak.util.JsonSerialization;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Theme resource
 *
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
@Path("/resources")
public class ThemeResource {

    private static final Logger logger = Logger.getLogger(ThemeResource.class);

    @Context
    private KeycloakSession session;

    @GET
    @Path("/localizations")
    @Produces(MediaType.APPLICATION_JSON)
    public Object getLocalizations(@QueryParam("realm") String realmName, @QueryParam("themeType") String themeType, @QueryParam("themeName") String themeName, @QueryParam("locale") String localeStr) {
        Locale locale = Locale.forLanguageTag(localeStr);

        Theme theme;
        try {
            theme = session.theme().getTheme(themeName, Theme.Type.valueOf(themeType.toUpperCase()));
        } catch (IOException var12) {
            String errorDesc = "Failed to create theme";
            logger.error(errorDesc, var12);
            return ErrorResponse.error(errorDesc, Response.Status.INTERNAL_SERVER_ERROR);
        }

        Properties messages = null;
        try {
            messages = theme.getMessages(locale);
        } catch (IOException e) {
            String errorDesc = "Unable to get messages for locale: " + locale;
            logger.error(errorDesc, e);
            return ErrorResponse.error(errorDesc, Response.Status.INTERNAL_SERVER_ERROR);
        }
        RealmModel realm = session.realms().getRealmByName(realmName);
        messages.putAll(realm.getRealmLocalizationTextsByLocale(locale.toLanguageTag()));

        return messagesToJsonString(messages);
    }

    /**
     * Get theme content
     *
     * @param themType
     * @param themeName
     * @param path
     * @return
     */
    @GET
    @Path("/{version}/{themeType}/{themeName}/{path:.*}")
    public Response getResource(@PathParam("version") String version, @PathParam("themeType") String themType, @PathParam("themeName") String themeName, @PathParam("path") String path) {
        if (!version.equals(Version.RESOURCES_VERSION)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            String contentType = MimeTypeUtil.getContentType(path);
            Theme theme = session.theme().getTheme(themeName, Theme.Type.valueOf(themType.toUpperCase()));
            ResourceEncodingProvider encodingProvider = session.theme().isCacheEnabled() ? ResourceEncodingHelper.getResourceEncodingProvider(session, contentType) : null;

            InputStream resource;
            if (encodingProvider != null) {
                resource = encodingProvider.getEncodedStream(() -> theme.getResourceAsStream(path), themType, themeName, path.replace('/', File.separatorChar));
            } else {
                resource = theme.getResourceAsStream(path);
            }

            if (resource != null) {
                Response.ResponseBuilder rb = Response.ok(resource).type(contentType).cacheControl(CacheControlUtil.getDefaultCacheControl());
                if (encodingProvider != null) {
                    rb.encoding(encodingProvider.getEncoding());
                }
                return rb.build();
            } else {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
        } catch (Exception e) {
            ServicesLogger.LOGGER.failedToGetThemeRequest(e);
            return Response.serverError().build();
        }
    }

    private String messagesToJsonString(Properties props) {
        if (props == null) return "";
        Properties newProps = new Properties();
        for (String prop: props.stringPropertyNames()) {
            newProps.put(prop, convertPropValue(props.getProperty(prop)));
        }
        try {
            return JsonSerialization.writeValueAsString(newProps);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    private String convertPropValue(String propertyValue) {
        // this mimics the behavior of java.text.MessageFormat used for the freemarker templates:
        // To print a single quote one needs to write two single quotes.
        // Single quotes will be stripped.
        // Usually single quotes would escape parameters, but this not implemented here.
        propertyValue = propertyValue.replaceAll("'('?)", "$1");
        propertyValue = putJavaParamsInNgTranslateFormat(propertyValue);

        return propertyValue;
    }

    // Put java resource bundle params in ngx-translate format
    // Do you like {0} and {1} ?
    //    becomes
    // Do you like {{param_0}} and {{param_1}} ?
    private String putJavaParamsInNgTranslateFormat(String propertyValue) {
        final Pattern bundleParamPattern = Pattern.compile("(\\{\\s*(\\d+)\\s*\\})");

        Matcher matcher = bundleParamPattern.matcher(propertyValue);
        while (matcher.find()) {
            propertyValue = propertyValue.replace(matcher.group(1), "{{param_" + matcher.group(2) + "}}");
        }

        return propertyValue;
    }

}
