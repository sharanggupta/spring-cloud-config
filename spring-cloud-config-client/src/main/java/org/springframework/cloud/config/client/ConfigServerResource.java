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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * {@link Resource} for a file that the Config Server serves next to the configuration of
 * the application, such as {@code nginx.conf}. The file is read from the plain text
 * endpoint of the server, which resolves the placeholders in it, or from the binary
 * endpoint, which returns it unchanged.
 * <p>
 * The file is fetched every time {@link #getInputStream()} is called, with the settings
 * of the config client. Create instances with a {@link ConfigServerResourceFactory}.
 *
 * @author Sharang Gupta
 * @since 5.1.0
 * @see ConfigServerResourceFactory
 */
public class ConfigServerResource extends AbstractResource {

	private final ConfigServerResourceFactory factory;

	private final String path;

	private final boolean binary;

	ConfigServerResource(ConfigServerResourceFactory factory, String path, boolean binary) {
		Assert.notNull(factory, "ConfigServerResourceFactory must not be null");
		Assert.hasText(path, "Path must not be empty");
		this.factory = factory;
		this.path = StringUtils.trimLeadingCharacter(path, '/');
		this.binary = binary;
		Assert.hasText(this.path, () -> "Path must name a file: " + path);
	}

	/**
	 * Return the path of the file on the Config Server, relative to the configuration of
	 * the application.
	 * @return the path of the file
	 */
	public String getPath() {
		return this.path;
	}

	/**
	 * Return whether the file is read from the binary endpoint of the Config Server, as
	 * it is stored, instead of the plain text endpoint, which resolves its placeholders.
	 * @return {@code true} if the file is read as a binary file
	 */
	public boolean isBinary() {
		return this.binary;
	}

	/**
	 * Fetch the file from the Config Server.
	 * @throws java.io.FileNotFoundException if no Config Server has the file
	 * @throws IOException if the file cannot be fetched
	 */
	@Override
	public InputStream getInputStream() throws IOException {
		return new ByteArrayInputStream(this.factory.read(this));
	}

	@Override
	public String getFilename() {
		return StringUtils.getFilename(this.path);
	}

	@Override
	public Resource createRelative(String relativePath) {
		return new ConfigServerResource(this.factory, StringUtils.applyRelativePath(this.path, relativePath),
				this.binary);
	}

	@Override
	public String getDescription() {
		String kind = (this.binary) ? "binary file" : "file";
		return "Config Server " + kind + " [" + this.path + "] for " + this.factory.describeCoordinates();
	}

	@Override
	public boolean equals(Object other) {
		return (this == other) || (other instanceof ConfigServerResource that && this.factory == that.factory
				&& this.path.equals(that.path) && this.binary == that.binary);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.path, this.binary);
	}

}
