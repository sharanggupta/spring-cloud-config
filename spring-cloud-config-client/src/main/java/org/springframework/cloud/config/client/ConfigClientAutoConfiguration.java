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

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.cloud.config.client.oauth2.ConfigClientOAuth2BootstrapConfiguration;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * Expose a ConfigClientProperties just so that there is a way to inspect the properties
 * bound to it. It won't be available in time for autowiring into the bootstrap context,
 * but the values in this properties object will be the same as the ones used to bind to
 * the config server, if there is one.
 *
 * @author Dave Syer
 * @author Marcos Barbero
 * @author Sharang Gupta
 *
 */
@Configuration(proxyBeanMethods = false)
public class ConfigClientAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public ConfigClientProperties configClientProperties(Environment environment, ApplicationContext context) {
		if (context.getParent() != null && BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context.getParent(),
				ConfigClientProperties.class).length > 0) {
			return BeanFactoryUtils.beanOfTypeIncludingAncestors(context.getParent(), ConfigClientProperties.class);
		}
		ConfigClientProperties client = new ConfigClientProperties(environment);
		return client;
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class })
	@ConditionalOnEnabledHealthIndicator("config")
	protected static class ConfigServerHealthIndicatorConfiguration {

		@Bean
		public ConfigClientHealthProperties configClientHealthProperties() {
			return new ConfigClientHealthProperties();
		}

		@Bean
		public ConfigServerHealthIndicator clientConfigServerHealthIndicator(ConfigClientHealthProperties properties,
				ConfigurableEnvironment environment) {
			return new ConfigServerHealthIndicator(environment, properties);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = ConfigClientProperties.PREFIX + ".enabled", matchIfMissing = true)
	protected static class ConfigServerResourceConfiguration {

		/**
		 * Used with the legacy bootstrap, or when the Config Server is not imported. The
		 * Config Data import registers its own factory, which uses the
		 * {@link org.springframework.web.client.RestTemplate} that loaded the
		 * configuration.
		 * @param properties the config client properties
		 * @param environment the environment
		 * @param oauth2Interceptor the OAuth2 interceptor of the legacy bootstrap, if any
		 * @return the factory
		 */
		@Bean
		@ConditionalOnMissingBean
		public ConfigServerResourceFactory configServerResourceFactory(ConfigClientProperties properties,
				Environment environment,
				@Qualifier(ConfigClientOAuth2BootstrapConfiguration.OAUTH2_INTERCEPTOR_BEAN_NAME) ObjectProvider<ClientHttpRequestInterceptor> oauth2Interceptor) {
			ConfigClientRequestTemplateFactory requestTemplateFactory = new ConfigClientRequestTemplateFactory(
					LogFactory.getLog(ConfigServerResourceFactory.class), properties.override(environment),
					oauth2Interceptor.stream().toList());
			return new ConfigServerResourceFactory(requestTemplateFactory, requestTemplateFactory::create, environment);
		}

		@Bean
		public static ConfigServerResourceProtocolResolver configServerResourceProtocolResolver() {
			return new ConfigServerResourceProtocolResolver();
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ContextRefresher.class)
	@ConditionalOnBean(ContextRefresher.class)
	@ConditionalOnProperty("spring.cloud.config.watch.enabled")
	protected static class ConfigClientWatchConfiguration {

		@Bean
		public ConfigClientWatch configClientWatch(ContextRefresher contextRefresher) {
			return new ConfigClientWatch(contextRefresher);
		}

	}

}
