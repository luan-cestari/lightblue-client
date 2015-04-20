package com.redhat.lightblue.client.http;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Objects;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.redhat.lightblue.client.LightblueClient;
import com.redhat.lightblue.client.LightblueClientConfiguration;
import com.redhat.lightblue.client.PropertiesLightblueClientConfiguration;
import com.redhat.lightblue.client.http.auth.HttpClientCertAuth;
import com.redhat.lightblue.client.http.auth.HttpClientNoAuth;
import com.redhat.lightblue.client.http.request.LightblueHttpDataRequest;
import com.redhat.lightblue.client.http.request.LightblueHttpMetadataRequest;
import com.redhat.lightblue.client.request.LightblueRequest;
import com.redhat.lightblue.client.response.LightblueResponse;
import com.redhat.lightblue.client.response.LightblueResponseParseException;
import com.redhat.lightblue.client.util.ClientConstants;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Objects;
import com.redhat.lightblue.client.util.JSON;

public class LightblueHttpClient implements LightblueClient {
    /**
     * It is safe and encouraged to share the same mapper among threads. It is thread safe. So,
     * this default instance is static.
     *
     * @see <a href="http://stackoverflow.com/a/3909846">The developer of the Jackson library's own
     * quote.</a>
     */
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper()
            .setDateFormat(ClientConstants.getDateFormat())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Logger LOGGER = LoggerFactory.getLogger(LightblueHttpClient.class);
    private final LightblueClientConfiguration configuration;
    private final ObjectMapper mapper;

    /**
     * This constructor will attempt to read the configuration from the default properties file on
     * the classpath.
     *
     * @see com.redhat.lightblue.client.PropertiesLightblueClientConfiguration
     */
    public LightblueHttpClient() {
        this(PropertiesLightblueClientConfiguration.fromDefault());
    }

    /**
     * This constructor will attempt to read the configuration from the specified properties file on
     * the file system.
     *
     * @see com.redhat.lightblue.client.PropertiesLightblueClientConfiguration
     */
    public LightblueHttpClient(String configFilePath) {
        this(PropertiesLightblueClientConfiguration.fromPath(Paths.get(configFilePath)));
    }

    /**
     * This constructor will use a copy of specified configuration object.
     */
    public LightblueHttpClient(LightblueClientConfiguration configuration) {
        this(configuration, JSON.getDefaultObjectMapper());
    }

    /**
     * This constructor will use a copy of specified configuration object and object mapper.
     * <p/>
     * <p>Without supplying an {@link com.fasterxml.jackson.databind.ObjectMapper} explicitly, a
     * default is shared among all threads ({@link #mapper}). It is injectable here because of best
     * practices: for further configuration support and unit testing.
     */
    public LightblueHttpClient(LightblueClientConfiguration configuration, ObjectMapper mapper) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(mapper, "mapper");

        // Make a defensive copy because configuration is mutable. This prevents alterations to the
        // config object from affecting this client after instantiation.
        this.configuration = new LightblueClientConfiguration(configuration);
        this.mapper = mapper;
    }

    /**
     * @deprecated Use LightblueHttpClient(String configFilePath) if you want to specify a config file location not on the classpath
     * Use LightblueHttpClient(LightblueClientConfiguration configuration) if you don't want to use config files at all
     */
    @Deprecated
    public LightblueHttpClient(String dataServiceURI, String metadataServiceURI, Boolean useCertAuth) {
        LightblueClientConfiguration configuration = new LightblueClientConfiguration();
        configuration.setDataServiceURI(dataServiceURI);
        configuration.setMetadataServiceURI(metadataServiceURI);
        configuration.setUseCertAuth(useCertAuth);
        this.configuration = configuration;
        this.mapper = JSON.getDefaultObjectMapper();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.redhat.lightblue.client.LightblueClient#metadata(com.redhat.lightblue
     * .client.request.LightblueRequest)
     */
    @Override
    public LightblueResponse metadata(LightblueRequest lightblueRequest) {
        LOGGER.debug("Calling metadata service with lightblueRequest: " + lightblueRequest.toString());
        return callService(new LightblueHttpMetadataRequest(lightblueRequest)
                .getRestRequest(configuration.getMetadataServiceURI()));
    }

    @Override
    public LightblueResponse data(LightblueRequest lightblueRequest) {
        LOGGER.debug("Calling data service with lightblueRequest: " + lightblueRequest.toString());
        try {
            return callService(new LightblueHttpDataRequest(lightblueRequest)
                    .getRestRequest(configuration.getDataServiceURI()));
        } catch (RuntimeException e) {
            throw new LightblueHttpClientException("Error sending lightblue request: " + lightblueRequest.getBody(), e);
        }
    }

    @Override
    public <T> T data(LightblueRequest lightblueRequest, Class<T> type) throws IOException {
        LightblueResponse response = data(lightblueRequest);
        try {
            return response.parseProcessed(type);
        } catch (RuntimeException | LightblueResponseParseException e) {
            throw new LightblueHttpClientException("Error sending lightblue request: " + lightblueRequest.getBody(), e);
        }
    }

    protected LightblueResponse callService(HttpRequestBase httpOperation) {
        String jsonOut;

        LOGGER.debug("Calling " + httpOperation);
        try {
            try (CloseableHttpClient httpClient = getLightblueHttpClient()) {
                httpOperation.setHeader("Content-Type", "application/json");

                if (LOGGER.isDebugEnabled()) {
                    try {
                        LOGGER.debug("Request body: " + (EntityUtils.toString(((HttpEntityEnclosingRequestBase) httpOperation).getEntity())));
                    } catch (ClassCastException e) {
                        LOGGER.debug("Request body: None");
                    }
                }

                long t1 = new Date().getTime();
                try (CloseableHttpResponse httpResponse = httpClient.execute(httpOperation)) {
                    HttpEntity entity = httpResponse.getEntity();
                    jsonOut = EntityUtils.toString(entity);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Response received from service: " + jsonOut);

                        long t2 = new Date().getTime();
                        LOGGER.debug("Call took "+(t2-t1)+"ms");
                    }
                    return new LightblueResponse(jsonOut, mapper);
                }
            }
        } catch (IOException e) {
            LOGGER.error("There was a problem calling the lightblue service", e);
            return new LightblueResponse("{\"error\":\"There was a problem calling the lightblue service\"}", mapper);
        }
    }

    private CloseableHttpClient getLightblueHttpClient() {
        CloseableHttpClient httpClient;
        if (configuration.useCertAuth()) {
            LOGGER.debug("Using certificate authentication");
            httpClient = new HttpClientCertAuth(configuration).getClient();
        } else {
            LOGGER.debug("Using no authentication");
            httpClient = new HttpClientNoAuth().getClient();
        }
        return httpClient;
    }

}