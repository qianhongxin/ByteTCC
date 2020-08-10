/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.spring;

import java.util.ArrayList;
import java.util.List;

import org.bytesoft.bytetcc.supports.spring.aware.CompensableContextAware;
import org.bytesoft.compensable.CompensableContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;

// 系统启动时，spring加载BeanDefinition后做的后置处理
// 对实现CompensableContextAware接口的Bean做一些处理
public class CompensableContextPostProcessor implements BeanFactoryPostProcessor {
	static final Logger logger = LoggerFactory.getLogger(CompensableContextPostProcessor.class);

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // 存储实现了 CompensableContext 类的类
		String targetBeanId = null;
		// 存储实现了CompensableContextAware类的类
		List<BeanDefinition> beanDefList = new ArrayList<BeanDefinition>();
		// 拿到已注入的beanName
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		// 遍历处理
		for (int i = 0; i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String beanClassName = beanDef.getBeanClassName();

			Class<?> beanClass = null;
			try {
			    // 主动加载一次
				beanClass = cl.loadClass(beanClassName);
			} catch (Exception ex) {
				logger.debug("Cannot load class {}, beanId= {}!", beanClassName, beanName, ex);
				continue;
			}

            // 判断beanClass是否实现了 CompensableContextAware 接口
			if (CompensableContextAware.class.isAssignableFrom(beanClass)) {
				beanDefList.add(beanDef);
			}

            // 判断beanClass是否实现了 CompensableContext 接口
			if (CompensableContext.class.isAssignableFrom(beanClass)) {
				if (targetBeanId == null) {
					targetBeanId = beanName;
				} else {
				    // 走到这，说明系统中有大于一个类实现了CompensableContext接口，直接抛出异常，启动失败
					throw new FatalBeanException("Duplicated compensable-context defined.");
				}
			}

		}


		for (int i = 0; targetBeanId != null && i < beanDefList.size(); i++) {
			BeanDefinition beanDef = beanDefList.get(i);
			MutablePropertyValues mpv = beanDef.getPropertyValues();
			RuntimeBeanReference beanRef = new RuntimeBeanReference(targetBeanId);
			// 给BeanDefinition加上属性compensableContext，并且类就是targetBeanId指向的类。方便后面生成类时注入
			mpv.addPropertyValue(CompensableContextAware.COMPENSABLE_CONTEXT_FIELD_NAME, beanRef);
		}

	}

}
