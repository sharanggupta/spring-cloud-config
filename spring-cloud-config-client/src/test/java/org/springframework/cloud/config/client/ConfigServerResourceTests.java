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
import java.net.SocketTimeoutException;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.cloud.config.client.ConfigClientProperties.MultipleUriStrategy;
import org.springframework.core.io.Resource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.ACCEPT_CHARSET;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests for {@link ConfigServerResource} and {@link ConfigServerResourceFactory}.
 *
 * @author Sharang Gupta
 */
class ConfigServerResourceTests {

	private static final String NGINX_CONF = "server_name example.com;";

	private final MockEnvironment environment = new MockEnvironment();

	private ConfigClientProperties properties;

	private MockRestServiceServer server;

	@BeforeEach
	void setUp() {
		this.properties = new ConfigClientProperties(this.environment);
		this.properties.setName("app");
		this.properties.setProfile("dev");
		this.properties.setLabel("main");
		this.properties.setUri(new String[] { "http://one:8888" });
	}

	@Test
	void readsPlainTextFileForTheApplicationProfileAndLabelOfTheClient() throws IOException {
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/nginx.conf"))
			.andExpect(method(GET))
			.andExpect(header(ACCEPT, TEXT_PLAIN_VALUE))
			.andExpect(header(ACCEPT_CHARSET, "utf-8"))
			.andRespond(withSuccess(NGINX_CONF, TEXT_PLAIN));

		Resource resource = factory.getResource("nginx.conf");

		assertThat(resource.getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void readsBinaryFileAsOctetStream() throws IOException {
		byte[] keystore = { 0, 1, 2, (byte) 0xCA, (byte) 0xFE };
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/keystore.p12"))
			.andExpect(header(ACCEPT, APPLICATION_OCTET_STREAM_VALUE))
			.andRespond(withSuccess(keystore, APPLICATION_OCTET_STREAM));

		Resource resource = factory.getBinaryResource("keystore.p12");

		assertThat(resource.getContentAsByteArray()).isEqualTo(keystore);
		this.server.verify();
	}

	@Test
	void readsFileInSubdirectory() throws IOException {
		ConfigServerResourceFactory factory = createFactory();
		expectNginxConfAt("http://one:8888/app/dev/main/nginx/nginx.conf");

		assertThat(factory.getResource("/nginx/nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void encodesThePathOfTheFile() throws IOException {
		ConfigServerResourceFactory factory = createFactory();
		expectNginxConfAt("http://one:8888/app/dev/main/sites%20available/default%3F.conf");

		assertThat(factory.getResource("sites available/default?.conf").getContentAsString(UTF_8))
			.isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void asksForTheDefaultLabelOfTheServerWhenNoLabelIsSet() throws IOException {
		this.properties.setLabel(null);
		ConfigServerResourceFactory factory = createFactory();
		expectNginxConfAt("http://one:8888/app/dev/nginx.conf?useDefaultLabel");

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void sendsLabelWithSlashInTheFormTheServerExpects() throws IOException {
		this.properties.setLabel("release/1.0");
		ConfigServerResourceFactory factory = createFactory();
		expectNginxConfAt("http://one:8888/app/dev/release(_)1.0/nginx.conf");

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void triesTheNextLabelWhenTheFileIsNotFound() throws IOException {
		this.properties.setLabel("feature, main");
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/feature/nginx.conf")).andRespond(withResourceNotFound());
		expectNginxConfAt("http://one:8888/app/dev/main/nginx.conf");

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void usesTheActiveProfilesWhenNoProfileIsSet() throws IOException {
		this.properties.setProfile(ConfigClientProperties.DEFAULT_PROFILE);
		this.environment.setActiveProfiles("prod", "eu");
		ConfigServerResourceFactory factory = createFactory();
		expectNginxConfAt("http://one:8888/app/prod,eu/main/nginx.conf");

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void triesTheNextUriWhenTheServerCannotBeReached() throws IOException {
		this.properties.setUri(new String[] { "http://one:8888", "http://two:8888" });
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/nginx.conf"))
			.andRespond(withException(new SocketTimeoutException("connect timed out")));
		expectNginxConfAt("http://two:8888/app/dev/main/nginx.conf");

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void triesTheNextUriAfterAnErrorResponseWithTheAlwaysStrategy() throws IOException {
		this.properties.setUri(new String[] { "http://one:8888", "http://two:8888" });
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/nginx.conf")).andRespond(withServerError());
		expectNginxConfAt("http://two:8888/app/dev/main/nginx.conf");

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void doesNotTryTheNextUriAfterAnErrorResponseWithTheConnectionTimeoutOnlyStrategy() {
		this.properties.setUri(new String[] { "http://one:8888", "http://two:8888" });
		this.properties.setMultipleUriStrategy(MultipleUriStrategy.CONNECTION_TIMEOUT_ONLY);
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/nginx.conf")).andRespond(withServerError());

		Resource resource = factory.getResource("nginx.conf");

		assertThatExceptionOfType(IOException.class).isThrownBy(resource::getInputStream)
			.isNotInstanceOf(FileNotFoundException.class)
			.withCauseInstanceOf(HttpServerErrorException.class);
		this.server.verify();
	}

	@Test
	void sendsTheUsernameAndPasswordOfTheClient() throws IOException {
		this.properties.setUsername("user");
		this.properties.setPassword("secret");
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/nginx.conf"))
			.andExpect(header(AUTHORIZATION, "Basic dXNlcjpzZWNyZXQ="))
			.andRespond(withSuccess(NGINX_CONF, TEXT_PLAIN));

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void sendsTheCredentialsOfEachUriWithoutPuttingThemInTheUrl() throws IOException {
		this.properties.setUri(new String[] { "http://alice:one@one:8888", "http://bob:two@two:8888" });
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/nginx.conf"))
			.andExpect(header(AUTHORIZATION, "Basic YWxpY2U6b25l"))
			.andRespond(withServerError());
		this.server.expect(requestTo("http://two:8888/app/dev/main/nginx.conf"))
			.andExpect(header(AUTHORIZATION, "Basic Ym9iOnR3bw=="))
			.andRespond(withSuccess(NGINX_CONF, TEXT_PLAIN));

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void sendsTheAuthorizationTokenAndHeadersOfTheClient() throws IOException {
		this.properties.getHeaders().put(ConfigClientProperties.AUTHORIZATION, "Bearer abc");
		this.properties.getHeaders().put("X-Tenant", "acme");
		this.properties.setToken("vault-token");
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/nginx.conf"))
			.andExpect(header(AUTHORIZATION, "Bearer abc"))
			.andExpect(header("X-Tenant", "acme"))
			.andExpect(header(ConfigClientProperties.TOKEN_HEADER, "vault-token"))
			.andRespond(withSuccess(NGINX_CONF, TEXT_PLAIN));

		assertThat(factory.getResource("nginx.conf").getContentAsString(UTF_8)).isEqualTo(NGINX_CONF);
		this.server.verify();
	}

	@Test
	void throwsFileNotFoundExceptionWhenNoServerHasTheFile() {
		this.properties.setUri(new String[] { "http://one:8888", "http://two:8888" });
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/missing.txt")).andRespond(withResourceNotFound());
		this.server.expect(requestTo("http://two:8888/app/dev/main/missing.txt")).andRespond(withResourceNotFound());

		Resource resource = factory.getResource("missing.txt");

		assertThatExceptionOfType(FileNotFoundException.class).isThrownBy(resource::getInputStream)
			.withMessageContaining("missing.txt");
		this.server.verify();
	}

	@Test
	void doesNotExistWhenTheServerDoesNotHaveTheFile() {
		ConfigServerResourceFactory factory = createFactory();
		this.server.expect(requestTo("http://one:8888/app/dev/main/missing.txt")).andRespond(withResourceNotFound());

		assertThat(factory.getResource("missing.txt").exists()).isFalse();
		this.server.verify();
	}

	@Test
	void describesTheFileWithTheCoordinatesOfTheClient() {
		ConfigServerResourceFactory factory = createFactory();

		ConfigServerResource resource = factory.getResource("nginx/nginx.conf");

		assertThat(resource.getFilename()).isEqualTo("nginx.conf");
		assertThat(resource.getDescription()).contains("nginx/nginx.conf", "'app'", "'dev'", "'main'");
	}

	@Test
	void createsRelativeResourceOfTheSameKind() throws IOException {
		ConfigServerResourceFactory factory = createFactory();

		Resource relative = factory.getBinaryResource("certs/server.p12").createRelative("ca.p12");

		assertThat(relative).isEqualTo(factory.getBinaryResource("certs/ca.p12"));
	}

	private ConfigServerResourceFactory createFactory() {
		ConfigClientRequestTemplateFactory requestTemplateFactory = new ConfigClientRequestTemplateFactory(
				LogFactory.getLog(getClass()), this.properties);
		RestTemplate restTemplate = requestTemplateFactory.create();
		this.server = MockRestServiceServer.bindTo(restTemplate).build();
		return new ConfigServerResourceFactory(requestTemplateFactory, () -> restTemplate, this.environment);
	}

	private void expectNginxConfAt(String url) {
		this.server.expect(requestTo(url)).andRespond(withSuccess(NGINX_CONF, TEXT_PLAIN));
	}

}
