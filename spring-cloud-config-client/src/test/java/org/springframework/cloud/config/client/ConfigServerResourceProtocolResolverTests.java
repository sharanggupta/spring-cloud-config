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

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.config.client.ConfigServerResourceProtocolResolver.BINARY_SUFFIX;
import static org.springframework.cloud.config.client.ConfigServerResourceProtocolResolver.PREFIX;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests for {@link ConfigServerResourceProtocolResolver}.
 *
 * @author Sharang Gupta
 */
class ConfigServerResourceProtocolResolverTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
				ConfigClientAutoConfiguration.class))
		.withPropertyValues("spring.application.name=app", "spring.cloud.config.profile=dev",
				"spring.cloud.config.label=main");

	@Test
	void resolvesPrefixedLocationsThroughTheResourceLoader() {
		this.contextRunner.run(context -> {
			Resource resource = context.getResource(PREFIX + "nginx/nginx.conf");

			assertThat(resource).isInstanceOfSatisfying(ConfigServerResource.class, configServerResource -> {
				assertThat(configServerResource.getPath()).isEqualTo("nginx/nginx.conf");
				assertThat(configServerResource.isBinary()).isFalse();
				assertThat(configServerResource.getDescription()).contains("'app'", "'dev'", "'main'");
			});
		});
	}

	@Test
	void resolvesBinaryLocations() {
		this.contextRunner.run(context -> {
			Resource resource = context.getResource(PREFIX + "keystore.p12" + BINARY_SUFFIX);

			assertThat(resource).isInstanceOfSatisfying(ConfigServerResource.class, configServerResource -> {
				assertThat(configServerResource.getPath()).isEqualTo("keystore.p12");
				assertThat(configServerResource.isBinary()).isTrue();
			});
		});
	}

	@Test
	void leavesOtherLocationsToTheResourceLoader() {
		this.contextRunner.run(context -> assertThat(context.getResource("classpath:applicationname.yaml"))
			.isInstanceOf(ClassPathResource.class));
	}

	@Test
	void readsFilesInjectedWithValue() {
		this.contextRunner.withUserConfiguration(MockServerConfiguration.class, FilesConfiguration.class)
			.run(context -> {
				MockRestServiceServer server = context.getBean(MockRestServiceServer.class);
				server.expect(requestTo("http://localhost:8888/app/dev/main/nginx.conf"))
					.andRespond(withSuccess("server_name example.com;", TEXT_PLAIN));
				server.expect(requestTo("http://localhost:8888/app/dev/main/keystore.p12"))
					.andExpect(header(ACCEPT, APPLICATION_OCTET_STREAM_VALUE))
					.andRespond(withSuccess(new byte[] { 1, 2, 3 }, APPLICATION_OCTET_STREAM));

				Files files = context.getBean(Files.class);

				assertThat(files.nginxConf().getContentAsString(UTF_8)).isEqualTo("server_name example.com;");
				assertThat(files.keystore().getContentAsByteArray()).containsExactly(1, 2, 3);
				server.verify();
			});
	}

	@Test
	void backsOffWhenTheConfigClientIsDisabled() {
		this.contextRunner.withPropertyValues("spring.cloud.config.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(ConfigServerResourceFactory.class);
			assertThat(context.getResource(PREFIX + "nginx.conf")).isNotInstanceOf(ConfigServerResource.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class MockServerConfiguration {

		private final RestTemplate restTemplate = new RestTemplate();

		@Bean
		MockRestServiceServer mockRestServiceServer() {
			return MockRestServiceServer.bindTo(this.restTemplate).build();
		}

		@Bean
		ConfigServerResourceFactory configServerResourceFactory(ConfigClientProperties properties,
				Environment environment) {
			ConfigClientRequestTemplateFactory requestTemplateFactory = new ConfigClientRequestTemplateFactory(
					LogFactory.getLog(getClass()), properties.override(environment));
			return new ConfigServerResourceFactory(requestTemplateFactory, () -> this.restTemplate, environment);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class FilesConfiguration {

		@Bean
		Files files(@Value(PREFIX + "nginx.conf") Resource nginxConf,
				@Value(PREFIX + "keystore.p12" + BINARY_SUFFIX) Resource keystore) {
			return new Files(nginxConf, keystore);
		}

	}

	record Files(Resource nginxConf, Resource keystore) {

	}

}
