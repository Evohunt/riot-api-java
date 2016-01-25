/*
 * Copyright 2016 Taylor Caldwell
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.rithms.riot.api.request;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import net.rithms.riot.api.ApiConfig;
import net.rithms.riot.api.RateLimitException;
import net.rithms.riot.api.RiotApiException;

/**
 * @author Daniel 'Linnun' Figge
 */
public class Request {
	public static final int CODE_SUCCESS_OK = 200;
	public static final int CODE_SUCCESS_NOCONTENT = 204;
	public static final int CODE_ERROR_BAD_REQUEST = 400;
	public static final int CODE_ERROR_UNAUTHORIZED = 401;
	public static final int CODE_ERROR_FORBIDDEN = 403;
	public static final int CODE_ERROR_NOT_FOUND = 404;
	public static final int CODE_ERROR_UNPROCESSABLE_ENTITY = 422;
	public static final int CODE_ERROR_RATE_LIMITED = 429;
	public static final int CODE_ERROR_SERVER_ERROR = 500;
	public static final int CODE_ERROR_SERVICE_UNAVAILABLE = 503;

	protected RequestState state = RequestState.NotSent;
	protected RequestMethod method = RequestMethod.GET;
	protected int timeout = 0;
	protected StringBuilder urlBase = new StringBuilder();
	protected Map<String, String> urlParameter = new HashMap<String, String>();
	protected String riotToken = null;
	protected String body = null;

	protected int responseCode = -1;
	protected String responseBody = null;

	protected final ApiConfig config;
	protected HttpURLConnection connection = null;
	protected Exception exception = null;

	public Request() {
		this(new ApiConfig());
	}

	public Request(ApiConfig config) {
		this.config = config;
		setTimeout(config.getTimeout());
	}

	public void addApiKeyToUrl() {
		addUrlParameter("api_key", config.getKey());
	}

	public void addTournamentKeyToRiotToken() {
		setRiotToken(config.getTournamentKey());
	}

	public void addUrlParameter(String key, Object value) {
		urlParameter.put(key, value.toString());
	}

	public void buildJsonBody(Map<String, Object> map) {
		body = new Gson().toJson(map);
	}

	public void cancel() {
		if (isDone()) {
			// Ignore
			return;
		}
		state = RequestState.Cancelled;
	}

	public synchronized void execute() throws RiotApiException, RateLimitException {
		if (state != RequestState.NotSent) {
			throw new IllegalStateException("The request has already been sent");
		}
		setState(RequestState.Waiting);

		try {
			URL url = new URL(getUrl());
			connection = (HttpURLConnection) url.openConnection();
			if (timeout > 0) {
				connection.setConnectTimeout(timeout);
				connection.setReadTimeout(timeout);
			}
			connection.setDoInput(true);
			connection.setInstanceFollowRedirects(false);
			connection.setRequestMethod(method.name());
			if (riotToken != null) {
				connection.setRequestProperty("X-Riot-Token", riotToken);
			}
			if (body != null) {
				connection.setRequestProperty("Content-Type", "application/json");
				connection.setDoOutput(true);
				DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
				dos.writeBytes(body);
				dos.flush();
				dos.close();
			}

			responseCode = connection.getResponseCode();
			if (responseCode == CODE_ERROR_RATE_LIMITED) {
				String retryAfterString = connection.getHeaderField("Retry-After");
				String rateLimitType = connection.getHeaderField("X-Rate-Limit-Type");
				if (retryAfterString != null) {
					int retryAfter = Integer.parseInt(retryAfterString);
					throw new RateLimitException(retryAfter, rateLimitType);
				} else {
					throw new RateLimitException(0, rateLimitType);
				}
			} else if (responseCode < 200 || responseCode >= 300) {
				throw new RiotApiException(responseCode);
			}

			StringBuilder responseBodyBuilder = new StringBuilder();
			if (responseCode != CODE_SUCCESS_NOCONTENT) {
				BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
				String line;
				while ((line = br.readLine()) != null) {
					responseBodyBuilder.append(line).append(System.lineSeparator());
				}
				br.close();
			}
			responseBody = responseBodyBuilder.toString();
			setState(RequestState.Succeeded);
		} catch (RiotApiException e) {
			exception = e;
			setState(RequestState.Failed);
			throw e;
		} catch (SocketTimeoutException e) {
			RiotApiException exception = new RiotApiException(RiotApiException.IOEXCEPTION);
			this.exception = exception;
			setState(RequestState.TimeOut);
			Logger.getLogger(Request.class.getName()).log(Level.FINE, null, e);
			throw exception;
		} catch (IOException e) {
			RiotApiException exception = new RiotApiException(RiotApiException.IOEXCEPTION);
			this.exception = exception;
			setState(RequestState.Failed);
			Logger.getLogger(Request.class.getName()).log(Level.SEVERE, null, e);
			throw exception;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	public <T> T getDto(Class<T> desiredDto) throws RiotApiException, RateLimitException {
		requireSucceededRequestState();
		if (responseCode == CODE_SUCCESS_NOCONTENT) {
			// The Riot Api is fine with the request, and explicitly sends no content
			return null;
		}
		T dto = null;
		try {
			dto = new Gson().fromJson(responseBody, desiredDto);
		} catch (JsonSyntaxException e) {
			RiotApiException exception = new RiotApiException(RiotApiException.PARSE_FAILURE);
			this.exception = exception;
			throw exception;
		}
		if (dto == null) {
			RiotApiException exception = new RiotApiException(RiotApiException.PARSE_FAILURE);
			this.exception = exception;
			throw exception;
		}
		return dto;
	}

	public <T> T getDto(Type desiredDto) throws RiotApiException, RateLimitException {
		requireSucceededRequestState();
		if (responseCode == CODE_SUCCESS_NOCONTENT) {
			// The Riot Api is fine with the request, and explicitly sends no content
			return null;
		}
		T dto = null;
		try {
			dto = new Gson().fromJson(responseBody, desiredDto);
		} catch (JsonSyntaxException e) {
			RiotApiException exception = new RiotApiException(RiotApiException.PARSE_FAILURE);
			this.exception = exception;
			throw exception;
		}
		if (dto == null) {
			RiotApiException exception = new RiotApiException(RiotApiException.PARSE_FAILURE);
			this.exception = exception;
			throw exception;
		}
		return dto;
	}

	public Exception getException() {
		if (!isFailed()) {
			return null;
		}
		return exception;
	}

	public String getResponseBody() {
		requireSucceededRequestState();
		return responseBody;
	}

	public int getResponseCode() {
		requireSucceededRequestState();
		return responseCode;
	}

	public int getTimeout() {
		return timeout;
	}

	protected String getUrl() {
		StringBuilder url = new StringBuilder(urlBase);
		char connector = !url.toString().contains("?") ? '?' : '&';
		for (String key : urlParameter.keySet()) {
			url.append(connector).append(key).append('=').append(urlParameter.get(key));
			connector = '&';
		}
		return url.toString();
	}

	public boolean isCancelled() {
		return state == RequestState.Cancelled;
	}

	public boolean isDone() {
		return (state != RequestState.NotSent && state != RequestState.Waiting);
	}

	public boolean isFailed() {
		return state == RequestState.Failed;
	}

	public boolean isPending() {
		return state == RequestState.Waiting;
	}

	public boolean isSuccessful() {
		return state == RequestState.Succeeded;
	}

	public boolean isTimeOut() {
		return state == RequestState.TimeOut;
	}

	protected void requireSucceededRequestState() {
		if (state == RequestState.NotSent) {
			throw new IllegalStateException("The request has not yet been sent");
		} else if (state == RequestState.Waiting) {
			throw new IllegalStateException("The request has not received a response yet");
		} else if (state == RequestState.Failed) {
			throw new IllegalStateException("The request has failed");
		}
	}

	public void setBody(String body) {
		this.body = body;
	}

	public void setMethod(RequestMethod method) {
		this.method = method;
	}

	protected void setRiotToken(String riotToken) {
		this.riotToken = riotToken;
	}

	protected boolean setState(RequestState state) {
		if (!isDone()) {
			this.state = state;
			return true;
		}
		return false;
	}

	protected void setTimeout(int timeout) {
		this.timeout = timeout;
		if (connection != null && timeout > 0) {
			connection.setConnectTimeout(timeout);
			connection.setReadTimeout(timeout);
		}
	}

	public void setUrlBase(Object... pieces) {
		urlBase = new StringBuilder();
		for (Object piece : pieces) {
			urlBase.append(piece);
		}
	}
}