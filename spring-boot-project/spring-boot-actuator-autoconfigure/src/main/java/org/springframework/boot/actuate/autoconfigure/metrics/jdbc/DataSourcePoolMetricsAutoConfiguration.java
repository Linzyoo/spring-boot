/*
 * Copyright 2012-2021 the original author or authors.
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

package org.springframework.boot.actuate.autoconfigure.metrics.jdbc;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.metrics.jdbc.DataSourcePoolMetrics;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.boot.jdbc.metadata.DataSourcePoolMetadataProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.log.LogMessage;
import org.springframework.util.StringUtils;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for metrics on all available
 * {@link DataSource datasources}.
 *
 * @author Stephane Nicoll
 * @since 2.0.0
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter({ MetricsAutoConfiguration.class, DataSourceAutoConfiguration.class,
		SimpleMetricsExportAutoConfiguration.class })
@ConditionalOnClass({ DataSource.class, MeterRegistry.class })
@ConditionalOnBean({ DataSource.class, MeterRegistry.class })
public class DataSourcePoolMetricsAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(DataSourcePoolMetadataProvider.class)
	static class DataSourcePoolMetadataMetricsConfiguration {

		private static final String DATASOURCE_SUFFIX = "dataSource";

		@Autowired
		void bindDataSourcesToRegistry(Map<String, DataSource> dataSources, MeterRegistry registry,
				ObjectProvider<DataSourcePoolMetadataProvider> metadataProviders) {
			List<DataSourcePoolMetadataProvider> metadataProvidersList = metadataProviders.stream()
					.collect(Collectors.toList());
			dataSources.forEach(
					(name, dataSource) -> bindDataSourceToRegistry(name, dataSource, metadataProvidersList, registry));
		}

		private void bindDataSourceToRegistry(String beanName, DataSource dataSource,
				Collection<DataSourcePoolMetadataProvider> metadataProviders, MeterRegistry registry) {
			String dataSourceName = getDataSourceName(beanName);
			new DataSourcePoolMetrics(dataSource, metadataProviders, dataSourceName, Collections.emptyList())
					.bindTo(registry);
		}

		/**
		 * Get the name of a DataSource based on its {@code beanName}.
		 *
		 * @param beanName the name of the data source bean
		 * @return a name for the given data source
		 */
		private String getDataSourceName(String beanName) {
			if (beanName.length() > DATASOURCE_SUFFIX.length()
					&& StringUtils.endsWithIgnoreCase(beanName, DATASOURCE_SUFFIX)) {
				return beanName.substring(0, beanName.length() - DATASOURCE_SUFFIX.length());
			}
			return beanName;
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(HikariDataSource.class)
	static class HikariDataSourceMetricsConfiguration {

		private static final Log logger = LogFactory.getLog(HikariDataSourceMetricsConfiguration.class);

		@Bean
		@Autowired
		public static HikariDataSourceBeanPostProcessor hikariDataSourceBeanPostProcessor(ApplicationContext applicationContext) {
			return new HikariDataSourceBeanPostProcessor(applicationContext);
		}

		static class HikariDataSourceBeanPostProcessor implements BeanPostProcessor {
			private final ApplicationContext context;

			HikariDataSourceBeanPostProcessor(ApplicationContext applicationContext) {
				this.context = applicationContext;
			}

			@Override
			public Object postProcessAfterInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {
				if (bean instanceof DataSource) {
					DataSource dataSource = (DataSource) bean;
					HikariDataSource hikariDataSource = DataSourceUnwrapper.unwrap(dataSource, HikariConfigMXBean.class,
							HikariDataSource.class);
					if (hikariDataSource != null) {
						bindMetricsRegistryToHikariDataSource(hikariDataSource);
					}
				}
				return bean;
			}

			private void bindMetricsRegistryToHikariDataSource(HikariDataSource hikari) {
				if (hikari.getMetricRegistry() == null && hikari.getMetricsTrackerFactory() == null) {
					try {
						MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
						hikari.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
					}
					catch (Exception ex) {
						logger.warn(LogMessage.format("Failed to bind Hikari metrics: %s", ex.getMessage()));
					}
				}
			}
		}
	}
}
