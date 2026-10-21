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

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ProtocolResolver;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * {@link ProtocolResolver} that resolves locations such as
 * {@code configserver-file:nginx.conf} to a {@link ConfigServerResource}, so that files
 * served by the Config Server can be injected with {@code @Value} or loaded with the
 * {@link ResourceLoader} of the application context. A location that ends with
 * {@value #BINARY_SUFFIX} is read as a binary file.
 * <p>
 * The resolver adds itself to the application context when it is post-processed, which is
 * before any bean that injects such a location is created. It uses the
 * {@link ConfigServerResourceFactory} bean to create the resources.
 *
 * @author Sharang Gupta
 * @since 5.1.0
 */
public class ConfigServerResourceProtocolResolver
		implements ProtocolResolver, ResourceLoaderAware, BeanFactoryPostProcessor {

	/**
	 * Prefix of the locations of files served by the Config Server.
	 */
	public static final String PREFIX = "configserver-file:";

	/**
	 * Suffix of the locations of binary files, which the Config Server returns unchanged.
	 */
	public static final String BINARY_SUFFIX = "?binary";

	private ResourceLoader resourceLoader;

	private BeanFactory beanFactory;

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		if (this.resourceLoader instanceof DefaultResourceLoader defaultResourceLoader) {
			defaultResourceLoader.addProtocolResolver(this);
		}
	}

	@Override
	public Resource resolve(String location, ResourceLoader resourceLoader) {
		if (!location.startsWith(PREFIX)) {
			return null;
		}
		String path = location.substring(PREFIX.length());
		ConfigServerResourceFactory factory = this.beanFactory.getBean(ConfigServerResourceFactory.class);
		if (path.endsWith(BINARY_SUFFIX)) {
			return factory.getBinaryResource(path.substring(0, path.length() - BINARY_SUFFIX.length()));
		}
		return factory.getResource(path);
	}

}
