package ai.api;

/***********************************************************************************************************************
 *
 * API.AI Java SDK - client-side libraries for API.AI
 * =================================================
 *
 * Copyright (C) 2015 by Speaktoit, Inc. (https://www.speaktoit.com)
 * https://www.api.ai
 *
 * *********************************************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 ***********************************************************************************************************************/

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import ai.api.util.IOUtils;
import ai.api.util.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import ai.api.http.HttpClient;
import ai.api.model.AIContext;
import ai.api.model.AIRequest;
import ai.api.model.AIResponse;
import ai.api.model.Entity;
import ai.api.model.Status;

/**
 * Do simple requests to the AI Service
 */
public class AIDataService {

    private static final Logger Log = LogManager.getLogger(AIDataService.class);
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

	/**
	 *  Cannot be <code>null</code>
	 */
    private final AIConfiguration config;
    
    /**
     * Cannot be <code>null</code>
     */
    private final AIServiceContext serviceContext;

    /**
     * Cannot be <code>null</code>
     */
    private final Gson gson = GsonFactory.getDefaultFactory().getGson();

    /**
     * Create new service for given configuration and some predefined service context 
     * @param config Service configuration data. Cannot be <code>null</code>
     * @param serviceContext Service context. If <code>null</code> then new context will be created
     * @throws IllegalArgumentException If config parameter is null
     */
    public AIDataService(final AIConfiguration config, final AIServiceContext serviceContext) {
    	if (config == null) {
    		throw new IllegalArgumentException("config should not be null");
    	}
        this.config = config.clone();
        if (serviceContext == null) {
        	this.serviceContext = new AIServiceContextBuilder().generateSessionId().build();
        } else {
        	this.serviceContext = serviceContext;
        }
    }
    
    /**
     * Create new service with unique context for given configuration 
     * @param config Service configuration data. Cannot be <code>null</code>
     * @throws IllegalArgumentException If config parameter is null
     */
    public AIDataService(final AIConfiguration config) {
    	this(config, null);
    }
    
    /**
     * @return Current context used in each request. Never <code>null</code>
     */
    public AIServiceContext getContext() {
    	return serviceContext;
    }

    /**
     * Make request to the ai service.
     *
     * @param request request object to the service. Cannot be <code>null</code>
     * @return response object from service. Never <code>null</code>
     */
    public AIResponse request(final AIRequest request) throws AIServiceException {
        return request(request, null);
    }

    /**
     * Make request to the ai service.
     *
     * @param request request object to the service. Cannot be <code>null</code>
     * @return response object from service. Never <code>null</code>
     */
    public AIResponse request(final AIRequest request, final RequestExtras requestExtras) throws AIServiceException {
        if (request == null) {
            throw new IllegalArgumentException("Request argument must not be null");
        }

        Log.debug("Start request");

        try {

            request.setLanguage(config.getApiAiLanguage());
            request.setSessionId(serviceContext.getSessionId());
            request.setTimezone(Calendar.getInstance().getTimeZone().getID());

            Map<String, String> additionalHeaders = null;

            if (requestExtras != null) {
                fillRequest(request, requestExtras);
                additionalHeaders = requestExtras.getAdditionalHeaders();
            }

            final String queryData = gson.toJson(request);
            final String response = doTextRequest(config.getQuestionUrl(serviceContext.getSessionId()), queryData, additionalHeaders);

            if (StringUtils.isEmpty(response)) {
                throw new AIServiceException("Empty response from ai service. Please check configuration and Internet connection.");
            }

            Log.debug("Response json: " + response.replaceAll("[\r\n]+", " "));

            final AIResponse aiResponse = gson.fromJson(response, AIResponse.class);

            if (aiResponse == null) {
                throw new AIServiceException("API.AI response parsed as null. Check debug log for details.");
            }

            if (aiResponse.isError()) {
                throw new AIServiceException(aiResponse);
            }

            aiResponse.cleanup();

            return aiResponse;

        } catch (final MalformedURLException e) {
            Log.error("Malformed url should not be raised", e);
            throw new AIServiceException("Wrong configuration. Please, connect to API.AI Service support", e);
        } catch (final JsonSyntaxException je) {
            throw new AIServiceException("Wrong service answer format. Please, connect to API.AI Service support", je);
        }

    }

    /**
     * Make requests to the ai service with voice data. This method must not be called in the UI Thread.
     *
     * @param voiceStream voice data stream for recognition.  Cannot be <code>null</code>
     * @return response object from service. Never <code>null</code>
     * @throws AIServiceException
     */
    public AIResponse voiceRequest(final InputStream voiceStream) throws AIServiceException {
        return voiceRequest(voiceStream, new RequestExtras());
    }

    /**
     * Make requests to the ai service with voice data. This method must not be called in the UI Thread.
     *
     * @param voiceStream voice data stream for recognition. Cannot be <code>null</code>
     * @param aiContexts additional contexts for request
     * @return response object from service. Never <code>null</code>
     * @throws AIServiceException
     */
    public AIResponse voiceRequest(final InputStream voiceStream, final List<AIContext> aiContexts) throws AIServiceException {
        return voiceRequest(voiceStream, new RequestExtras(aiContexts, null));
    }

    /**
     * Make requests to the ai service with voice data. This method must not be called in the UI Thread.
     *
     * @param voiceStream voice data stream for recognition. Cannot be <code>null</code>
     * @param requestExtras object that can hold additional contexts and entities
     * @return response object from service. Never <code>null</code>
     * @throws AIServiceException
     */
    public AIResponse voiceRequest(final InputStream voiceStream, final RequestExtras requestExtras) throws AIServiceException {
    	assert voiceStream != null;
        Log.debug("Start voice request");

        try {
            final AIRequest request = new AIRequest();

            request.setLanguage(config.getApiAiLanguage());
            request.setSessionId(serviceContext.getSessionId());
            request.setTimezone(Calendar.getInstance().getTimeZone().getID());

            Map<String, String> additionalHeaders = null;

            if (requestExtras != null) {
                fillRequest(request, requestExtras);
                additionalHeaders = requestExtras.getAdditionalHeaders();
            }

            final String queryData = gson.toJson(request);

            Log.debug("Request json: " + queryData);

            final String response = doSoundRequest(voiceStream, queryData, additionalHeaders);

            if (StringUtils.isEmpty(response)) {
                throw new AIServiceException("Empty response from ai service. Please check configuration.");
            }

            Log.debug("Response json: " + response);

            final AIResponse aiResponse = gson.fromJson(response, AIResponse.class);

            if (aiResponse == null) {
                throw new AIServiceException("API.AI response parsed as null. Check debug log for details.");
            }

            if (aiResponse.isError()) {
                throw new AIServiceException(aiResponse);
            }

            aiResponse.cleanup();

            return aiResponse;

        } catch (final MalformedURLException e) {
            Log.error("Malformed url should not be raised", e);
            throw new AIServiceException("Wrong configuration. Please, connect to AI Service support", e);
        } catch (final JsonSyntaxException je) {
            throw new AIServiceException("Wrong service answer format. Please, connect to API.AI Service support", je);
        }
    }

    /**
     * Forget all old contexts
     *
     * @return true if operation succeed, false otherwise
     */
    public boolean resetContexts() {
        final AIRequest cleanRequest = new AIRequest();
        cleanRequest.setQuery("empty_query_for_resetting_contexts"); // TODO remove it after protocol fix
        cleanRequest.setResetContexts(true);
        try {
            final AIResponse response = request(cleanRequest);
            return !response.isError();
        } catch (final AIServiceException e) {
            Log.error("Exception while contexts clean.", e);
            return false;
        }
    }

    public AIResponse uploadUserEntity(final Entity userEntity) throws AIServiceException {
        return uploadUserEntities(Collections.singleton(userEntity));
    }

    public AIResponse uploadUserEntities(final Collection<Entity> userEntities) throws AIServiceException {
        if (userEntities == null || userEntities.size() == 0) {
            throw new AIServiceException("Empty entities list");
        }

        final String requestData = gson.toJson(userEntities);
        try {
            final String response = doTextRequest(config.getUserEntitiesEndpoint(serviceContext.getSessionId()), requestData);
            if (StringUtils.isEmpty(response)) {
                throw new AIServiceException("Empty response from ai service. Please check configuration and Internet connection.");
            }
            Log.debug("Response json: " + response);

            final AIResponse aiResponse = gson.fromJson(response, AIResponse.class);

            if (aiResponse == null) {
                throw new AIServiceException("API.AI response parsed as null. Check debug log for details.");
            }

            if (aiResponse.isError()) {
                throw new AIServiceException(aiResponse);
            }

            aiResponse.cleanup();
            return aiResponse;

        } catch (final MalformedURLException e) {
            Log.error("Malformed url should not be raised", e);
            throw new AIServiceException("Wrong configuration. Please, connect to AI Service support", e);
        } catch (final JsonSyntaxException je) {
            throw new AIServiceException("Wrong service answer format. Please, connect to API.AI Service support", je);
        }
    }

    protected String doTextRequest(final String requestJson) throws MalformedURLException, AIServiceException {
        return doTextRequest(config.getQuestionUrl(serviceContext.getSessionId()), requestJson);
    }

    protected String doTextRequest(final String endpoint, final String requestJson) throws MalformedURLException, AIServiceException {
        return doTextRequest(endpoint, requestJson, null);
    }
    
    /**
     * 
     * @param endpoint Cannot be <code>null</code>
     * @param requestJson Cannot be <code>null</code>
     * @param additionalHeaders
     * @return
     * @throws MalformedURLException
     * @throws AIServiceException
     */
    protected String doTextRequest(final String endpoint,
                                   final String requestJson, 
                                   final Map<String, String> additionalHeaders) throws MalformedURLException, AIServiceException {

    	assert endpoint != null;
    	assert requestJson != null;
        HttpURLConnection connection = null;

        try {

            final URL url = new URL(endpoint);

            final String queryData = requestJson;

            Log.debug("Request json: " + queryData);

            if (config.getProxy() != null) {
                connection = (HttpURLConnection) url.openConnection(config.getProxy());
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.addRequestProperty("Authorization", "Bearer " + config.getApiKey());
            connection.addRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.addRequestProperty("Accept", "application/json");

            if (additionalHeaders != null) {
                for (final Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
                    connection.addRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            connection.connect();

            final BufferedOutputStream outputStream = new BufferedOutputStream(connection.getOutputStream());
            IOUtils.writeAll(queryData, outputStream, DEFAULT_CHARSET);
            outputStream.close();

            final InputStream inputStream = new BufferedInputStream(connection.getInputStream());
            final String response = IOUtils.readAll(inputStream, DEFAULT_CHARSET);
            inputStream.close();

            return response;
        } catch (final IOException e) {
            if (connection != null) {
                try {
                    final InputStream errorStream = connection.getErrorStream();
                    if (errorStream != null) {
                        final String errorString = IOUtils.readAll(errorStream, DEFAULT_CHARSET);
                        Log.debug(errorString);
                        return errorString;
                    }
                    else {
                        throw new AIServiceException("Can't connect to the api.ai service.", e);
                    }
                } catch (final IOException ex) {
                    Log.warn("Can't read error response", ex);
                }
            }
            Log.error("Can't make request to the API.AI service. Please, check connection settings and API access token.", e);
            throw new AIServiceException("Can't make request to the API.AI service. Please, check connection settings and API access token.", e);

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

    }

    /**
     * Method extracted for testing purposes
     * @param voiceStream Cannot be <code>null</code>
     * @param queryData Cannot be <code>null</code>
     */
    protected String doSoundRequest(final InputStream voiceStream, final String queryData) throws MalformedURLException, AIServiceException {
        return doSoundRequest(voiceStream, queryData, null);
    }

    /**
     * Method extracted for testing purposes
     * @param voiceStream Cannot be <code>null</code>
     * @param queryData Cannot be <code>null</code>
     */
    protected String doSoundRequest(final InputStream voiceStream,
                                    final String queryData,
                                    final Map<String, String> additionalHeaders) throws MalformedURLException, AIServiceException {

    	assert voiceStream != null;
    	assert queryData != null;
        HttpURLConnection connection = null;
        HttpClient httpClient = null;

        try {
            final URL url = new URL(config.getQuestionUrl(serviceContext.getSessionId()));
            
            Log.debug("Connecting to {}", url);

            if (config.getProxy() != null) {
                connection = (HttpURLConnection) url.openConnection(config.getProxy());
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }

            connection.addRequestProperty("Authorization", "Bearer " + config.getApiKey());
            connection.addRequestProperty("Accept", "application/json");

            if (additionalHeaders != null) {
                for (final Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
                    connection.addRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);

            httpClient = new HttpClient(connection);
            httpClient.setWriteSoundLog(config.isWriteSoundLog());

            httpClient.connectForMultipart();
            httpClient.addFormPart("request", queryData);
            httpClient.addFilePart("voiceData", "voice.wav", voiceStream);
            httpClient.finishMultipart();

            final String response = httpClient.getResponse();
            return response;

        } catch (final IOException e) {
            if (httpClient != null) {
                final String errorString = httpClient.getErrorString();
                Log.debug(errorString);
                if (!StringUtils.isEmpty(errorString)) {
                    return errorString;
                } else if (e instanceof HttpRetryException) {
                    final AIResponse response = new AIResponse();
                    final int code = ((HttpRetryException) e).responseCode();
                    final Status status = Status.fromResponseCode(code);
                    status.setErrorDetails(((HttpRetryException) e).getReason());
                    response.setStatus(status);
                    throw new AIServiceException(response);
                }
            }

            Log.error("Can't make request to the API.AI service. Please, check connection settings and API.AI keys.", e);
            throw new AIServiceException("Can't make request to the API.AI service. Please, check connection settings and API.AI keys.", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void fillRequest(final AIRequest request, final RequestExtras requestExtras) {
    	assert request != null;
    	assert requestExtras != null;
        if (requestExtras.hasContexts()) {
            request.setContexts(requestExtras.getContexts());
        }

        if (requestExtras.hasEntities()) {
            request.setEntities(requestExtras.getEntities());
        }

        if (requestExtras.getLocation() != null) {
            request.setLocation(requestExtras.getLocation());
        }
    }
}
