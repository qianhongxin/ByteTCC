/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc.supports.springcloud;

import java.util.ArrayList;
import java.util.List;

import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

// 系统启动时，spring加载BeanDefinition后做的后置处理
// 对实现CompensableEndpointAware接口的Bean做一些处理
public class SpringCloudEndpointPostProcessor implements BeanFactoryPostProcessor, EnvironmentAware {
	static final Logger logger = LoggerFactory.getLogger(SpringCloudEndpointPostProcessor.class);

	private Environment environment;

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		// 存储实现了CompensableEndpointAware接口的类
		List<BeanDefinition> beanDefList = new ArrayList<BeanDefinition>();
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		// 遍历所有的BeanDefinition，找到实现了CompensableEndpointAware接口的类
		for (int i = 0; i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String beanClassName = beanDef.getBeanClassName();

			Class<?> beanClass = null;
			try {
				beanClass = cl.loadClass(beanClassName);
			} catch (Exception ex) {
				logger.debug("Cannot load class {}, beanId= {}!", beanClassName, beanName, ex);
				continue;
			}

			if (CompensableEndpointAware.class.isAssignableFrom(beanClass)) {
				beanDefList.add(beanDef);
			}
		}

		// 获取本机ip
		String host = CommonUtils.getInetAddress();
		// 获取应用名称
		String name = this.environment.getProperty("spring.application.name");
		// 获取服务端口
		String port = this.environment.getProperty("server.port");
		// 格式化
		String identifier = String.format("%s:%s:%s", host, name, port);

		// 遍历所有实现了CompensableEndpointAware接口的BeanDefinition
		for (int i = 0; i < beanDefList.size(); i++) {
			BeanDefinition beanDef = beanDefList.get(i);
			MutablePropertyValues mpv = beanDef.getPropertyValues();
			// 给BeanDefinition添加endpoint属性，值是identifier
			mpv.addPropertyValue(CompensableEndpointAware.ENDPOINT_FIELD_NAME, identifier);
		}

	}

}
