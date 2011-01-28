/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.CallbackFilter;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;

class EarlyBeanReferenceProxyCreator {

	static final String FINAL_CLASS_ERROR_MESSAGE =
		"Cannot create subclass proxy for bean type %s because it is a final class. " +
		"Make the class non-final or inject the bean by interface rather than by concrete class.";

	static final String MISSING_NO_ARG_CONSTRUCTOR_ERROR_MESSAGE =
		"Cannot create subclass proxy for bean type %s because it does not have a no-arg constructor. " +
		"Add a no-arg constructor or attempt to inject the bean by interface rather than by concrete class.";

	static final String PRIVATE_NO_ARG_CONSTRUCTOR_ERROR_MESSAGE =
		"Cannot create subclass proxy for bean type %s because its no-arg constructor is private. " +
		"Increase the visibility of the no-arg constructor or attempt to inject the bean by interface rather " +
		"than by concrete class.";


	private final ConfigurableListableBeanFactory beanFactory;
	private final EarlyBeanReferenceProxyStatus earlyProxyStatus = new EarlyBeanReferenceProxyStatus();


	public EarlyBeanReferenceProxyCreator(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	public Object createProxy(DependencyDescriptor dd) {
		Class<?> beanType = dd.getDependencyType();
		MethodInterceptor interceptor = new DereferenceTargetBeanInterceptor(dd, this.earlyProxyStatus, this.beanFactory);

		return doCreateProxy(this.beanFactory, this.earlyProxyStatus, beanType, interceptor);
	}

	private static Object doCreateProxy(ConfigurableListableBeanFactory beanFactory, EarlyBeanReferenceProxyStatus earlyProxyStatus, Class<?> beanType, MethodInterceptor interceptor) {
		Enhancer enhancer = new Enhancer();
		if (beanType.isInterface()) {
			enhancer.setSuperclass(Object.class);
			enhancer.setInterfaces(new Class<?>[] {beanType, EarlyBeanReferenceProxy.class});
		} else {
			assertClassIsProxyCapable(beanType);
			enhancer.setSuperclass(beanType);
			enhancer.setInterfaces(new Class<?>[] {EarlyBeanReferenceProxy.class});
		}
		enhancer.setCallbacks(new Callback[] {
			new EarlyBeanReferenceProxyMethodInterceptor(beanFactory, earlyProxyStatus),
			new ToStringInterceptor(),
			new EqualsAndHashCodeInterceptor(),
			interceptor,
			new TargetBeanDelegatingMethodInterceptor()
		});
		enhancer.setCallbackFilter(new CallbackFilter() {
			public int accept(Method method) {
				if (AnnotationUtils.findAnnotation(method, Bean.class) != null) {
					return 0;
				}
				if (method.getName().equals("toString")) {
					return 1;
				}
				if (method.getName().equals("hashCode") || method.getName().equals("equals")) {
					return 2;
				}
				if (method.getName().equals("dereferenceTargetBean")) {
					return 3;
				}
				return 4;
			}
		});
		return enhancer.create();
	}

	private static void assertClassIsProxyCapable(Class<?> beanType) {
		if ((beanType.getModifiers() & Modifier.FINAL) != 0) {
			throw new ProxyCreationException(String.format(FINAL_CLASS_ERROR_MESSAGE, beanType.getName()));
		}
		try {
			// attempt to retrieve the no-arg constructor for the class
			Constructor<?> noArgCtor = beanType.getDeclaredConstructor();
			if ((noArgCtor.getModifiers() & Modifier.PRIVATE) != 0) {
				throw new ProxyCreationException(String.format(PRIVATE_NO_ARG_CONSTRUCTOR_ERROR_MESSAGE, beanType.getName()));
			}
		} catch (NoSuchMethodException ex) {
			throw new ProxyCreationException(String.format(MISSING_NO_ARG_CONSTRUCTOR_ERROR_MESSAGE, beanType.getName()));
		}
	}

	static class ToStringInterceptor implements MethodInterceptor {

		public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
			return String.format("EarlyBeanReferenceProxy for bean of type %s", obj.getClass().getSuperclass().getSimpleName());
		}

	}

	static class EqualsAndHashCodeInterceptor implements MethodInterceptor {

		public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
			throw new UnsupportedOperationException("equals() and hashCode() methods on [%s] should not be called as " +
					"doing so will cause premature instantiation of the target bean object. This may have occurred " +
					"because the proxied object was added to a collection.");
		}

	}

	static class DereferenceTargetBeanInterceptor implements MethodInterceptor {

		private final DependencyDescriptor dd;
		private final EarlyBeanReferenceProxyStatus earlyProxyStatus;
		private final ConfigurableListableBeanFactory beanFactory;

		public DereferenceTargetBeanInterceptor(DependencyDescriptor dd, EarlyBeanReferenceProxyStatus status, ConfigurableListableBeanFactory beanFactory) {
			this.dd = dd;
			this.earlyProxyStatus = status;
			this.beanFactory = beanFactory;
		}

		public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
			try {
				earlyProxyStatus.createEarlyProxies = false;
				return this.beanFactory.resolveDependency(dd, null); // TODO: is 'null' for beanName appropriate?
			} finally {
				earlyProxyStatus.createEarlyProxies = true;
			}
		}

	}


	static class GetBeanInterceptor implements MethodInterceptor {

		private final String beanName;
		private final EarlyBeanReferenceProxyStatus earlyProxyStatus;
		private final ConfigurableListableBeanFactory beanFactory;

		public GetBeanInterceptor(String beanName, EarlyBeanReferenceProxyStatus status, ConfigurableListableBeanFactory beanFactory) {
			this.beanName = beanName;
			this.earlyProxyStatus = status;
			this.beanFactory = beanFactory;
		}

		public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
			try {
				earlyProxyStatus.createEarlyProxies = false;
				return this.beanFactory.getBean(beanName); // TODO: deal with aliases / alternate bean name
			} finally {
				earlyProxyStatus.createEarlyProxies = true;
			}
		}

	}


	static class TargetBeanDelegatingMethodInterceptor implements MethodInterceptor {

		public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
			/* TODO: logging?
			System.out.printf("TargetBeanDelegatingMethodInterceptor.intercept(): attempting to retreive object of " +
					"type %s and to delegate call to method %s() made against %s\n",
					obj.getClass().getSuperclass(), method.getName(), this);
			*/
			Object targetBean = ((EarlyBeanReferenceProxy)obj).dereferenceTargetBean();
			return method.invoke(targetBean, args);
		}

	}


	static class EarlyBeanReferenceProxyMethodInterceptor implements MethodInterceptor {

		private final ConfigurableListableBeanFactory beanFactory;
		private final EarlyBeanReferenceProxyStatus earlyProxyStatus;

		public EarlyBeanReferenceProxyMethodInterceptor(ConfigurableListableBeanFactory beanFactory, EarlyBeanReferenceProxyStatus earlyBeanReferenceProxyStatus) {
			this.beanFactory = beanFactory;
			this.earlyProxyStatus = earlyBeanReferenceProxyStatus;
		}

		public Object intercept(Object obj, final Method beanMethod, Object[] args, MethodProxy proxy) throws Throwable {
			Assert.state(earlyProxyStatus.createEarlyProxies == true,
					"EarlyBeanReferenceProxyStatus must be true when intercepting a method call");

			Class<?> returnType = beanMethod.getReturnType();
			MethodInterceptor interceptor = new GetBeanInterceptor(beanMethod.getName(), this.earlyProxyStatus, this.beanFactory);
			return doCreateProxy(this.beanFactory, this.earlyProxyStatus, returnType, interceptor);
		}
	}


	static class EarlyBeanReferenceProxyStatus {

		boolean createEarlyProxies = true;

	}
}

