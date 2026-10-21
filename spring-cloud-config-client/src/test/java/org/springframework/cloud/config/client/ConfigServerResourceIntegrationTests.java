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

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.config.client.ConfigServerResourceProtocolResolver.PREFIX;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests that {@link ConfigServerResourceFactory} reaches the application context with the
 * Config Data import and with the legacy bootstrap.
 *
 * @author Sharang Gupta
 */
class ConfigServerResourceIntegrationTests {

	@Test
	void readsFilesWithTheRestTemplateOfTheConfigDataImport() throws Exception {
		RestTemplate restTemplate = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
		server.expect(ExpectedCount.manyTimes(), requestTo("http://one:8888/app/default/main"))
			.andRespond(
					withSuccess("{\"name\":\"app\",\"profiles\":[\"default\"],\"label\":\"main\"}", APPLICATION_JSON));
		server.expect(requestTo("http://one:8888/app/default/main/nginx.conf"))
			.andRespond(withSuccess("server_name example.com;", TEXT_PLAIN));

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfig.class)
			.web(WebApplicationType.NONE)
			.addBootstrapRegistryInitializer(
					ConfigServerBootstrapper.create().withRestTemplateFactory(bootstrapContext -> restTemplate))
			.run("--spring.config.import=configserver:http://one:8888", "--spring.application.name=app",
					"--spring.cloud.config.label=main")) {

			Resource resource = context.getResource(PREFIX + "nginx.conf");

			assertThat(context.getBeansOfType(ConfigServerResourceFactory.class)).hasSize(1);
			assertThat(resource.getContentAsString(UTF_8)).isEqualTo("server_name example.com;");
			server.verify();
		}
	}

	@Test
	void createsResourcesForTheBootstrapConfiguration() {
		String uri = "http://localhost:" + TestSocketUtils.findAvailableTcpPort();

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfig.class)
			.web(WebApplicationType.NONE)
			.properties("spring.cloud.bootstrap.enabled=true")
			.run("--spring.cloud.config.uri=" + uri, "--spring.cloud.config.name=app",
					"--spring.cloud.config.profile=dev", "--spring.cloud.config.label=main")) {

			Resource resource = context.getResource(PREFIX + "nginx.conf");

			assertThat(context.getBeansOfType(ConfigServerResourceFactory.class)).hasSize(1);
			assertThat(resource).isInstanceOf(ConfigServerResource.class);
			assertThat(resource.getDescription()).contains("'app'", "'dev'", "'main'");
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class TestConfig {

	}

}
