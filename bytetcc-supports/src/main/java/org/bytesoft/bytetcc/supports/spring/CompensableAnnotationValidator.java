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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.CompensableCancel;
import org.bytesoft.compensable.CompensableConfirm;
import org.bytesoft.compensable.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 检查bean定义中的Compensable注解是否符合使用规范
// 该类在系统启动时，有spring加载处理
public class CompensableAnnotationValidator implements BeanFactoryPostProcessor {
	static final Logger logger = LoggerFactory.getLogger(CompensableAnnotationValidator.class);

	// 对 bytetcc标注的接口做校验，看是否符合bytetcc的使用规范
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		Map<String, Class<?>> otherServiceMap = new HashMap<String, Class<?>>();
		Map<String, Compensable> compensables = new HashMap<String, Compensable>();

		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		// 处理系统注册的所有的bean
		for (int i = 0; beanNameArray != null && i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String className = beanDef.getBeanClassName();
			Class<?> clazz = null;

			try {
			    // 加载类到jvm虚拟机，因为类是使用才加载，对于延迟初始化的对象，类是没有加载的，所以这里提前加载了
				clazz = cl.loadClass(className);
			} catch (Exception ex) {
				logger.debug("Cannot load class {}, beanId= {}!", className, beanName, ex);
				continue;
			}

			Compensable compensable = null;
			try {
			    // 获取类上标注的@Compensable注解
				compensable = clazz.getAnnotation(Compensable.class);
			} catch (RuntimeException rex) {
				logger.warn("Error occurred while getting @Compensable annotation, class= {}!", clazz, rex);
			}

			// 针对compensable是否为空，设置到对应map中存储
			if (compensable == null) {
				otherServiceMap.put(beanName, clazz);
				// 如果不是被@Compensable修饰的类，直接结束当前循环进入下一层循环
				continue;
			} else {
				compensables.put(beanName, compensable);
			}

			try {
			    // 获取compensable的interfaceClass属性值
				Class<?> interfaceClass = compensable.interfaceClass();
				// 根据bytetcc要求，这个属性值必须是一个接口，否则保存
				if (interfaceClass.isInterface() == false) {
					throw new IllegalStateException("Compensable's interfaceClass must be a interface.");
				}
				// 获取interfaceClass接口的所有的方法（针对每个定义的方法，都应该有try，comfirm，cancel逻辑）
				Method[] methodArray = interfaceClass.getDeclaredMethods();
				for (int j = 0; j < methodArray.length; j++) {
					Method interfaceMethod = methodArray[j];
					Method method = clazz.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
					// 校验Simplified属性为true的tcc方法
					this.validateSimplifiedCompensable(method, clazz);
					// 校验RemotingException
					this.validateDeclaredRemotingException(method, clazz);
					// 校验TransactionalPropagation
					this.validateTransactionalPropagation(method, clazz);
				}
			} catch (IllegalStateException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			} catch (NoSuchMethodException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			} catch (SecurityException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			} catch (RuntimeException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			}
		}

		// 这里处理 Simplified属性为false的tcc方法
		Iterator<Map.Entry<String, Compensable>> itr = compensables.entrySet().iterator();
		while (itr.hasNext()) {
			Map.Entry<String, Compensable> entry = itr.next();
			Compensable compensable = entry.getValue();
			Class<?> interfaceClass = compensable.interfaceClass();
			// 获取confirmableKey指向的值
			String confirmableKey = compensable.confirmableKey();
            // 获取cancelableKey指向的值
			String cancellableKey = compensable.cancellableKey();

            // confirmableKey 校验
			if (StringUtils.isNotBlank(confirmableKey)) {
				if (compensables.containsKey(confirmableKey)) {
				    // 走到这，说明标注了Compensable注解的对象集合compensables中有confirmableKey指向的类的对象
                    // 即confirm的实现类和try的类一样。这是不允许的，所以这里报错
					throw new FatalBeanException(
							String.format("The confirm bean(id= %s) cannot be a compensable service!", confirmableKey));
				}
				// 从普通的类集合中拿confirmableKey指向的类
				Class<?> clazz = otherServiceMap.get(confirmableKey);
				if (clazz == null) {
				    // 走到这，说明confirmableKey指向的类不在spring管理的容器中啊，报错即可
					throw new IllegalStateException(String.format("The confirm bean(id= %s) is not exists!", confirmableKey));
				}

				try {
					Method[] methodArray = interfaceClass.getDeclaredMethods();
					for (int j = 0; j < methodArray.length; j++) {
						Method interfaceMethod = methodArray[j];
						// 获取confirm类的方法method
						Method method = clazz.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
						// 校验RemotingException
						this.validateDeclaredRemotingException(method, clazz);
                        // 校验TransactionalPropagation
						this.validateTransactionalPropagation(method, clazz);
                        // 校验TransactionalRollbackFor
						this.validateTransactionalRollbackFor(method, clazz, confirmableKey);
					}
				} catch (IllegalStateException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				} catch (NoSuchMethodException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				} catch (SecurityException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				}
			}

			// cancellableKey校验
			if (StringUtils.isNotBlank(cancellableKey)) {
				if (compensables.containsKey(cancellableKey)) {
					throw new FatalBeanException(
							String.format("The cancel bean(id= %s) cannot be a compensable service!", confirmableKey));
				}
				Class<?> clazz = otherServiceMap.get(cancellableKey);
				if (clazz == null) {
					throw new IllegalStateException(String.format("The cancel bean(id= %s) is not exists!", cancellableKey));
				}

				try {
					Method[] methodArray = interfaceClass.getDeclaredMethods();
					for (int j = 0; j < methodArray.length; j++) {
						Method interfaceMethod = methodArray[j];
						Method method = clazz.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
						this.validateDeclaredRemotingException(method, clazz);
						this.validateTransactionalPropagation(method, clazz);
						this.validateTransactionalRollbackFor(method, clazz, cancellableKey);
					}
				} catch (IllegalStateException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				} catch (NoSuchMethodException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				} catch (SecurityException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				}

			}
		}
	}

	private void validateSimplifiedCompensable(Method method, Class<?> clazz) throws IllegalStateException {
		Compensable compensable = clazz.getAnnotation(Compensable.class);
		Class<?> interfaceClass = compensable.interfaceClass();
		Method[] methods = interfaceClass.getDeclaredMethods();
		// simplified为true时，try接口对应的comfirm和cancel方法可以放在一个类中。而且这里的method，举例见就是demo的consumer的SimplifiedController的transfer方法
        // simplified为false时，try，confirm，cancel需要放在三个类中，参见demo的consumer
        // 以下就是校验逻辑：
            // 如果compensable.simplified()是true，则try接口不应该有CompensableConfirm或CompensableCancel接口修饰，并且interfaceClass只能申明一个方法
		if (compensable.simplified() == false) {
			return;
		} else if (method.getAnnotation(CompensableConfirm.class) != null) {
			throw new FatalBeanException(
					String.format("The try method(%s) can not be the same as the confirm method!", method));
		} else if (method.getAnnotation(CompensableCancel.class) != null) {
			throw new FatalBeanException(String.format("The try method(%s) can not be the same as the cancel method!", method));
		} else if (methods != null && methods.length > 1) {
			throw new FatalBeanException(String.format(
					"The interface bound by @Compensable(simplified= true) supports only one method, class= %s!", clazz));
		}

		// 走到这说明compensable.simplified() == true成立
		Class<?>[] parameterTypes = method.getParameterTypes();
		Method[] methodArray = clazz.getDeclaredMethods();

		CompensableConfirm confirmable = null;
		CompensableCancel cancellable = null;
		for (int i = 0; i < methodArray.length; i++) {
			Method element = methodArray[i];
			Class<?>[] paramTypes = element.getParameterTypes();
			// 获取confirm方法的CompensableConfirm
			CompensableConfirm confirm = element.getAnnotation(CompensableConfirm.class);
			// 获取 方法的CompensableCancel
			CompensableCancel cancel = element.getAnnotation(CompensableCancel.class);
			if (confirm == null && cancel == null) {
			    // 如果都是空，说明该方法是普通方法，放过即可
				continue;
			} else if (Arrays.equals(parameterTypes, paramTypes) == false) {
				throw new FatalBeanException(
						String.format("The parameter types of confirm/cancel method({}) is different from the try method({})!",
								element, method));
			} else if (confirm != null) {
				if (confirmable != null) {
				    // 走到这，说明一个类中声明了不止一个CompensableConfirm标注的方法，则报错
					throw new FatalBeanException(
							String.format("There are more than one confirm method specified, class= %s!", clazz));
				} else {
					confirmable = confirm;
				}
			} else if (cancel != null) {
				if (cancellable != null) {
                    // 走到这，说明一个类中声明了不止一个 CompensableCancel 标注的方法，则报错
					throw new FatalBeanException(
							String.format("There are more than one cancel method specified, class= %s!", clazz));
				} else {
					cancellable = cancel;
				}
			}

		}

	}

	// 校验方法是否抛出了RemotingException异常，不允许抛出
	private void validateDeclaredRemotingException(Method method, Class<?> clazz) throws IllegalStateException {
	    Class<?>[] exceptionTypeArray = method.getExceptionTypes();

		boolean located = false;
		for (int i = 0; i < exceptionTypeArray.length; i++) {
			Class<?> exceptionType = exceptionTypeArray[i];
			if (RemotingException.class.isAssignableFrom(exceptionType)) {
				located = true;
				break;
			}
		}

		if (located) {
			throw new FatalBeanException(String.format(
					"The method(%s) shouldn't be declared to throw a remote exception: org.bytesoft.compensable.RemotingException!",
					method));
		}

	}

	// 校验事务传播属性
	private void validateTransactionalPropagation(Method method, Class<?> clazz) throws IllegalStateException {
		Transactional transactional = method.getAnnotation(Transactional.class);
		if (transactional == null) {
			Class<?> declaringClass = method.getDeclaringClass();
			transactional = declaringClass.getAnnotation(Transactional.class);
		}

		if (transactional == null) {
			throw new IllegalStateException(String.format("Method(%s) must be specificed a Transactional annotation!", method));
		}
		Propagation propagation = transactional.propagation();
		if (Propagation.REQUIRED.equals(propagation) == false //
				&& Propagation.MANDATORY.equals(propagation) == false //
				&& Propagation.SUPPORTS.equals(propagation) == false //
		                && Propagation.REQUIRES_NEW.equals(propagation) == false) {
			throw new IllegalStateException(
					String.format("Method(%s) not support propagation level: %s!", method, propagation.name()));
		}
	}

	private void validateTransactionalRollbackFor(Method method, Class<?> clazz, String beanName) throws IllegalStateException {
		Transactional transactional = method.getAnnotation(Transactional.class);
		if (transactional == null) {
		    // 如果方法没有标注Transactional注解，则从方法所属的类身上获取Transactional注解
			Class<?> declaringClass = method.getDeclaringClass();
			transactional = declaringClass.getAnnotation(Transactional.class);
		}

		if (transactional == null) {
		    // 走到这，说明没有标注Transactional注解，不符合规则，抛出异常
			throw new IllegalStateException(String.format("Method(%s) must be specificed a Transactional annotation!", method));
		}

		String[] rollbackForClassNameArray = transactional.rollbackForClassName();
		if (rollbackForClassNameArray != null && rollbackForClassNameArray.length > 0) {
		    // confirm/cancel方法不支持指定rollbackForClassName回滚
			throw new IllegalStateException(String.format(
					"The transactional annotation on the confirm/cancel class does not support the property rollbackForClassName yet(beanId= %s)!",
					beanName));
		}

		// 支持rollbackFor回滚
		Class<?>[] rollErrorArray = transactional.rollbackFor();

		// 校验是否匹配返回异常和抛出异常
		Class<?>[] errorTypeArray = method.getExceptionTypes();
		for (int j = 0; errorTypeArray != null && j < errorTypeArray.length; j++) {
			Class<?> errorType = errorTypeArray[j];
			if (RuntimeException.class.isAssignableFrom(errorType)) {
				continue;
			}

			boolean matched = false;
			for (int k = 0; rollErrorArray != null && k < rollErrorArray.length; k++) {
				Class<?> rollbackError = rollErrorArray[k];
				if (rollbackError.isAssignableFrom(errorType)) {
					matched = true;
					break;
				}
			}

			if (matched == false) {
				throw new IllegalStateException(
						String.format("The value of Transactional.rollbackFor annotated on method(%s) must includes %s!",
								method, errorType.getName()));
			}
		}
	}

}
