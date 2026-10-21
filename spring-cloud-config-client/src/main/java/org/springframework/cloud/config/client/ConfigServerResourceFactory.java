/*
 * Copyright 2013-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.client;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.config.client.ConfigClientProperties.Credentials;
import org.springframework.cloud.config.client.ConfigClientProperties.MultipleUriStrategy;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import static org.springframework.cloud.config.client.ConfigClientProperties.DEFAULT_APPLICATION;
import static org.springframework.cloud.config.client.ConfigClientProperties.DEFAULT_PROFILE;
import static org.springframework.cloud.config.client.ConfigClientProperties.TOKEN_HEADER;
import static org.springframework.cloud.config.environment.Environment.denormalize;

/**
 * Creates {@link ConfigServerResource}s for the files that the Config Server serves next
 * to the configuration of the application, and reads them with the settings of the config
 * client.
 * <p>
 * Files are requested for the application name, profiles and label of the client. When no
 * label is set, the server uses its default label. When the label is a comma-separated
 * list, each label is tried in turn until one has the file. The URIs of the client are
 * tried in order, following its {@link ConfigClientProperties#getMultipleUriStrategy()
 * multiple URI strategy}, with its credentials, token and headers, and with the TLS
 * settings and timeouts of its {@link RestTemplate}.
 * <p>
 * The client auto-configuration registers a bean of this type.
 *
 * @author Sharang Gupta
 * @since 5.1.0
 * @see ConfigServerResourceProtocolResolver
 */
public class ConfigServerResourceFactory {

	private static final Log log = LogFactory.getLog(ConfigServerResourceFactory.class);

	private static final String FILE_PATH = "/{name}/{profile}/{label}/{path}";

	private static final String DEFAULT_LABEL_FILE_PATH = "/{name}/{profile}/{path}?useDefaultLabel";

	private static final String DEFAULT_LABEL = "";

	private final ConfigClientRequestTemplateFactory requestTemplateFactory;

	private final Supplier<RestTemplate> restTemplate;

	private final Environment environment;

	/**
	 * Create a new instance.
	 * @param requestTemplateFactory the request settings of the config client, with the
	 * {@link ConfigClientProperties} that locate the files
	 * @param restTemplate supplies the {@link RestTemplate} to fetch the files with,
	 * called when the first file is read
	 * @param environment the environment whose active profiles are used when the config
	 * client sets no profile
	 */
	public ConfigServerResourceFactory(ConfigClientRequestTemplateFactory requestTemplateFactory,
			Supplier<RestTemplate> restTemplate, Environment environment) {
		Assert.notNull(requestTemplateFactory, "ConfigClientRequestTemplateFactory must not be null");
		Assert.notNull(restTemplate, "RestTemplate supplier must not be null");
		Assert.notNull(environment, "Environment must not be null");
		this.requestTemplateFactory = requestTemplateFactory;
		this.restTemplate = SingletonSupplier.of(restTemplate);
		this.environment = environment;
	}

	/**
	 * Return a resource for a plain text file. The Config Server resolves the
	 * placeholders in the file with the configuration of the application.
	 * @param path the path of the file, relative to the configuration of the application
	 * @return the resource
	 */
	public ConfigServerResource getResource(String path) {
		return new ConfigServerResource(this, path, false);
	}

	/**
	 * Return a resource for a binary file, which the Config Server returns unchanged.
	 * @param path the path of the file, relative to the configuration of the application
	 * @return the resource
	 */
	public ConfigServerResource getBinaryResource(String path) {
		return new ConfigServerResource(this, path, true);
	}

	byte[] read(ConfigServerResource resource) throws IOException {
		for (String label : getLabels()) {
			byte[] content = readWithLabel(resource, label.trim());
			if (content != null) {
				return content;
			}
		}
		throw new FileNotFoundException(resource.getDescription() + " cannot be opened because it does not exist");
	}

	String describeCoordinates() {
		String label = getProperties().getLabel();
		return "application '" + getName() + "', profile '" + getProfile() + "' and "
				+ (StringUtils.hasText(label) ? "label '" + label + "'" : "the default label of the server");
	}

	/**
	 * Read the file from the first URI that answers, or return {@code null} if the file
	 * is not found with this label.
	 */
	private byte[] readWithLabel(ConfigServerResource resource, String label) throws IOException {
		ConfigClientProperties properties = getProperties();
		int numberOfUris = properties.getUri().length;
		for (int i = 0; i < numberOfUris; i++) {
			Credentials credentials = properties.getCredentials(i);
			boolean hasNextUri = i < numberOfUris - 1;
			try {
				ResponseEntity<byte[]> response = exchange(credentials, resource, label);
				if (response.getStatusCode().is2xxSuccessful()) {
					return (response.getBody() != null) ? response.getBody() : new byte[0];
				}
				if (hasNextUri && tryNextUriOnAnyError()) {
					logTryingNextUri(credentials, "Status : " + response.getStatusCode());
					continue;
				}
				throw new IOException(resource.getDescription() + " could not be read, the Config Server answered "
						+ response.getStatusCode());
			}
			catch (HttpClientErrorException | HttpServerErrorException ex) {
				if (hasNextUri && tryNextUriOnAnyError()) {
					logTryingNextUri(credentials, "Error : " + ex.getMessage());
					continue;
				}
				if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
					return null;
				}
				throw new IOException(resource.getDescription() + " could not be read", ex);
			}
			catch (ResourceAccessException ex) {
				if (hasNextUri) {
					logTryingNextUri(credentials, "Error : " + ex.getMessage());
					continue;
				}
				throw new IOException(resource.getDescription() + " could not be read", ex);
			}
		}
		return null;
	}

	private ResponseEntity<byte[]> exchange(Credentials credentials, ConfigServerResource resource, String label) {
		HttpEntity<Void> request = new HttpEntity<>(null, createHeaders(credentials, resource.isBinary()));
		return this.restTemplate.get()
			.exchange(createFileUri(credentials.getUri(), resource.getPath(), label), HttpMethod.GET, request,
					byte[].class);
	}

	/**
	 * Expand the path of the file before encoding it, so that the slashes of a file in a
	 * subdirectory separate path segments, whatever the encoding mode of the
	 * {@link RestTemplate}.
	 */
	private URI createFileUri(String serverUri, String path, String label) {
		if (StringUtils.hasText(label)) {
			return UriComponentsBuilder.fromUriString(serverUri + FILE_PATH)
				.buildAndExpand(getName(), getProfile(), denormalize(label), path)
				.encode()
				.toUri();
		}
		return UriComponentsBuilder.fromUriString(serverUri + DEFAULT_LABEL_FILE_PATH)
			.buildAndExpand(getName(), getProfile(), path)
			.encode()
			.toUri();
	}

	private HttpHeaders createHeaders(Credentials credentials, boolean binary) {
		ConfigClientProperties properties = getProperties();
		HttpHeaders headers = new HttpHeaders();
		if (binary) {
			headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
		}
		else {
			headers.setAccept(List.of(MediaType.TEXT_PLAIN));
			headers.setAcceptCharset(List.of(properties.getCharset()));
		}
		this.requestTemplateFactory.addAuthorizationToken(headers, credentials.getUsername(),
				credentials.getPassword());
		if (StringUtils.hasText(properties.getToken())) {
			headers.add(TOKEN_HEADER, properties.getToken());
		}
		return headers;
	}

	private void logTryingNextUri(Credentials credentials, String reason) {
		log.info("Failed to fetch a file from the config server at : " + credentials.getUri()
				+ ". Will try the next url if available. " + reason);
	}

	private boolean tryNextUriOnAnyError() {
		return getProperties().getMultipleUriStrategy() == MultipleUriStrategy.ALWAYS;
	}

	private String[] getLabels() {
		String label = getProperties().getLabel();
		if (!StringUtils.hasText(label)) {
			return new String[] { DEFAULT_LABEL };
		}
		return StringUtils.commaDelimitedListToStringArray(label);
	}

	private String getName() {
		String name = getProperties().getName();
		return StringUtils.hasText(name) ? name : DEFAULT_APPLICATION;
	}

	/**
	 * Return the profile of the config client, or the active profiles of the application
	 * when the config client sets none, as the Config Data loader does.
	 */
	private String getProfile() {
		String profile = getProperties().getProfile();
		if (StringUtils.hasText(profile) && !DEFAULT_PROFILE.equals(profile)) {
			return profile;
		}
		String[] activeProfiles = this.environment.getActiveProfiles();
		if (activeProfiles.length > 0) {
			return StringUtils.arrayToCommaDelimitedString(activeProfiles);
		}
		return StringUtils.arrayToCommaDelimitedString(this.environment.getDefaultProfiles());
	}

	private ConfigClientProperties getProperties() {
		return this.requestTemplateFactory.getProperties();
	}

}
