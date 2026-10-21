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

package org.springframework.cloud.config.server;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.config.client.ConfigClientAutoConfiguration;
import org.springframework.cloud.config.client.ConfigServerResourceProtocolResolver;
import org.springframework.cloud.config.server.test.TestConfigServerApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Reads files from a running Config Server with the
 * {@link ConfigServerResourceProtocolResolver#PREFIX} locations of the config client.
 *
 * @author Sharang Gupta
 */
@SpringBootTest(classes = TestConfigServerApplication.class, properties = "spring.config.name:configserver",
		webEnvironment = RANDOM_PORT)
@ActiveProfiles("native")
class ConfigClientResourceIntegrationTests {

	private static final byte[] BINARY_CONTENT = { (byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF, (byte) 0xFE };

	@TempDir
	static Path repository;

	@LocalServerPort
	private int port;

	@BeforeAll
	static void writeRepository() throws IOException {
		Files.writeString(repository.resolve("application.yml"), "nginx.server.name: example.com\n");
		Files.writeString(repository.resolve("nginx.conf"), "server_name ${nginx.server.name};");
		Files.createDirectory(repository.resolve("sites"));
		Files.writeString(repository.resolve("sites/default.conf"), "listen ${nginx.port:80};");
		Files.write(repository.resolve("logo.png"), BINARY_CONTENT);
	}

	@DynamicPropertySource
	static void nativeSearchLocations(DynamicPropertyRegistry registry) {
		registry.add("spring.cloud.config.server.native.search-locations", () -> repository.toUri().toString());
	}

	@Test
	void readsPlainTextFileWithItsPlaceholdersResolved() throws IOException {
		try (ConfigurableApplicationContext client = runClient()) {
			Resource resource = client.getResource(ConfigServerResourceProtocolResolver.PREFIX + "nginx.conf");

			assertThat(resource.getContentAsString(UTF_8)).isEqualTo("server_name example.com;");
		}
	}

	@Test
	void readsBinaryFileUnchanged() throws IOException {
		try (ConfigurableApplicationContext client = runClient()) {
			Resource resource = client.getResource(ConfigServerResourceProtocolResolver.PREFIX + "logo.png"
					+ ConfigServerResourceProtocolResolver.BINARY_SUFFIX);

			assertThat(resource.getContentAsByteArray()).isEqualTo(BINARY_CONTENT);
		}
	}

	@Test
	void readsFileInSubdirectoryWithTheLabelOfTheClient() throws IOException {
		try (ConfigurableApplicationContext client = runClient("--spring.cloud.config.label=master")) {
			Resource resource = client.getResource(ConfigServerResourceProtocolResolver.PREFIX + "sites/default.conf");

			assertThat(resource.getContentAsString(UTF_8)).isEqualTo("listen 80;");
		}
	}

	@Test
	void throwsFileNotFoundExceptionForMissingFile() {
		try (ConfigurableApplicationContext client = runClient()) {
			Resource resource = client.getResource(ConfigServerResourceProtocolResolver.PREFIX + "missing.conf");

			assertThat(resource.exists()).isFalse();
			assertThatExceptionOfType(FileNotFoundException.class).isThrownBy(resource::getInputStream);
		}
	}

	private ConfigurableApplicationContext runClient(String... args) {
		String[] clientArgs = Stream
			.concat(Stream.of("--spring.config.import=configserver:http://localhost:" + this.port,
					"--spring.application.name=app", "--spring.cloud.config.enabled=true"), Stream.of(args))
			.toArray(String[]::new);
		return new SpringApplicationBuilder(ClientApplication.class).web(WebApplicationType.NONE).run(clientArgs);
	}

	@SpringBootConfiguration
	@ImportAutoConfiguration({ ConfigurationPropertiesAutoConfiguration.class,
			PropertyPlaceholderAutoConfiguration.class, ConfigClientAutoConfiguration.class })
	static class ClientApplication {

	}

}
