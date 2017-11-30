package com.alibaba.dubbo.config.spring.beans.factory.annotation;

import com.alibaba.dubbo.config.annotation.DubboReference;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.springframework.core.BridgeMethodResolver.findBridgedMethod;
import static org.springframework.core.BridgeMethodResolver.isVisibilityBridgeMethodPair;
import static org.springframework.core.annotation.AnnotationUtils.findAnnotation;
import static org.springframework.core.annotation.AnnotationUtils.getAnnotation;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that Consumer service {@link DubboReference} annotated fields
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 * @since 2.5.7
 */
public class ReferenceAnnotationBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter
        implements MergedBeanDefinitionPostProcessor, PriorityOrdered, ApplicationContextAware, BeanClassLoaderAware,
        DisposableBean {

    /**
     * The bean name of {@link ReferenceAnnotationBeanPostProcessor}
     */
    public static final String BEAN_NAME = "referenceAnnotationBeanPostProcessor";

    private final Log logger = LogFactory.getLog(getClass());

    private ApplicationContext applicationContext;

    private ClassLoader classLoader;

    private final ConcurrentMap<String, InjectionMetadata> injectionMetadataCache =
            new ConcurrentHashMap<String, InjectionMetadata>(256);

    private final ConcurrentMap<String, ReferenceBean<?>> referenceBeansCache =
            new ConcurrentHashMap<String, ReferenceBean<?>>();

    @Override
    public PropertyValues postProcessPropertyValues(
            PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) throws BeanCreationException {

        InjectionMetadata metadata = findReferenceMetadata(beanName, bean.getClass(), pvs);
        try {
            metadata.inject(bean, beanName, pvs);
        } catch (BeanCreationException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new BeanCreationException(beanName, "Injection of @DubboReference dependencies failed", ex);
        }
        return pvs;
    }


    /**
     * Finds {@link InjectionMetadata.InjectedElement} Metadata from annotated {@link DubboReference @DubboReference} fields
     *
     * @param beanClass The {@link Class} of Bean
     * @return non-null {@link List}
     */
    private List<InjectionMetadata.InjectedElement> findFieldReferenceMetadata(final Class<?> beanClass) {

        final List<InjectionMetadata.InjectedElement> elements = new LinkedList<InjectionMetadata.InjectedElement>();

        ReflectionUtils.doWithFields(beanClass, new ReflectionUtils.FieldCallback() {
            @Override
            public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {

                DubboReference dubboReference = getAnnotation(field, DubboReference.class);

                if (dubboReference != null) {

                    if (Modifier.isStatic(field.getModifiers())) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("@DubboReference annotation is not supported on static fields: " + field);
                        }
                        return;
                    }

                    elements.add(new ReferenceFieldElement(field, dubboReference));
                }

            }
        });

        return elements;

    }

    /**
     * Finds {@link InjectionMetadata.InjectedElement} Metadata from annotated {@link DubboReference @DubboReference} methods
     *
     * @param beanClass The {@link Class} of Bean
     * @return non-null {@link List}
     */
    private List<InjectionMetadata.InjectedElement> findMethodReferenceMetadata(final Class<?> beanClass) {

        final List<InjectionMetadata.InjectedElement> elements = new LinkedList<InjectionMetadata.InjectedElement>();

        ReflectionUtils.doWithMethods(beanClass, new ReflectionUtils.MethodCallback() {
            @Override
            public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {

                Method bridgedMethod = findBridgedMethod(method);

                if (!isVisibilityBridgeMethodPair(method, bridgedMethod)) {
                    return;
                }

                DubboReference dubboReference = findAnnotation(bridgedMethod, DubboReference.class);

                if (dubboReference != null && method.equals(ClassUtils.getMostSpecificMethod(method, beanClass))) {
                    if (Modifier.isStatic(method.getModifiers())) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("@DubboReference annotation is not supported on static methods: " + method);
                        }
                        return;
                    }
                    if (method.getParameterTypes().length == 0) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("@DubboReference  annotation should only be used on methods with parameters: " +
                                    method);
                        }
                    }
                    PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, beanClass);
                    elements.add(new ReferenceMethodElement(method, pd, dubboReference));
                }
            }
        });

        return elements;

    }


    /**
     * @param beanClass
     * @return
     */
    private InjectionMetadata buildReferenceMetadata(final Class<?> beanClass) {

        final List<InjectionMetadata.InjectedElement> elements = new LinkedList<InjectionMetadata.InjectedElement>();

        elements.addAll(findFieldReferenceMetadata(beanClass));

        elements.addAll(findMethodReferenceMetadata(beanClass));

        return new InjectionMetadata(beanClass, elements);

    }

    private InjectionMetadata findReferenceMetadata(String beanName, Class<?> clazz, PropertyValues pvs) {
        // Fall back to class name as cache key, for backwards compatibility with custom callers.
        String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
        // Quick check on the concurrent map first, with minimal locking.
        InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
        if (InjectionMetadata.needsRefresh(metadata, clazz)) {
            synchronized (this.injectionMetadataCache) {
                metadata = this.injectionMetadataCache.get(cacheKey);
                if (InjectionMetadata.needsRefresh(metadata, clazz)) {
                    if (metadata != null) {
                        metadata.clear(pvs);
                    }
                    try {
                        metadata = buildReferenceMetadata(clazz);
                        this.injectionMetadataCache.put(cacheKey, metadata);
                    } catch (NoClassDefFoundError err) {
                        throw new IllegalStateException("Failed to introspect bean class [" + clazz.getName() +
                                "] for dubboReference metadata: could not find class that it depends on", err);
                    }
                }
            }
        }
        return metadata;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
        if (beanType != null) {
            InjectionMetadata metadata = findReferenceMetadata(beanName, beanType, null);
            metadata.checkConfigMembers(beanDefinition);
        }
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    @Override
    public void destroy() throws Exception {

        for (ReferenceBean referenceBean : referenceBeansCache.values()) {
            if (logger.isInfoEnabled()) {
                logger.info(referenceBean + " was destroying!");
            }
            referenceBean.destroy();
        }

        injectionMetadataCache.clear();
        referenceBeansCache.clear();

        if (logger.isInfoEnabled()) {
            logger.info(getClass() + " was destroying!");
        }

    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * {@link DubboReference} {@link Method} {@link InjectionMetadata.InjectedElement}
     */
    private class ReferenceMethodElement extends InjectionMetadata.InjectedElement {

        private final Method method;

        private final DubboReference dubboReference;

        protected ReferenceMethodElement(Method method, PropertyDescriptor pd, DubboReference dubboReference) {
            super(method, pd);
            this.method = method;
            this.dubboReference = dubboReference;
        }

        @Override
        protected void inject(Object bean, String beanName, PropertyValues pvs) throws Throwable {

            Class<?> referenceClass = pd.getPropertyType();

            Object referenceBean = buildReferenceBean(dubboReference, referenceClass);

            ReflectionUtils.makeAccessible(method);

            method.invoke(bean, referenceBean);

        }

    }

    /**
     * {@link DubboReference} {@link Field} {@link InjectionMetadata.InjectedElement}
     */
    private class ReferenceFieldElement extends InjectionMetadata.InjectedElement {

        private final Field field;

        private final DubboReference dubboReference;

        protected ReferenceFieldElement(Field field, DubboReference dubboReference) {
            super(field, null);
            this.field = field;
            this.dubboReference = dubboReference;
        }

        @Override
        protected void inject(Object bean, String beanName, PropertyValues pvs) throws Throwable {

            Class<?> referenceClass = field.getType();

            Object referenceBean = buildReferenceBean(dubboReference, referenceClass);

            ReflectionUtils.makeAccessible(field);

            field.set(bean, referenceBean);

        }

    }

    private Object buildReferenceBean(DubboReference dubboReference, Class<?> referenceClass) throws Exception {

        String referenceBeanCacheKey = generateReferenceBeanCacheKey(dubboReference, referenceClass);

        ReferenceBean<?> referenceBean = referenceBeansCache.get(referenceBeanCacheKey);

        if (referenceBean == null) {

            ReferenceBeanBuilder beanBuilder = ReferenceBeanBuilder
                    .create(dubboReference, classLoader, applicationContext)
                    .interfaceClass(referenceClass);

            referenceBean = beanBuilder.build();

            referenceBeansCache.putIfAbsent(referenceBeanCacheKey, referenceBean);

        }


        return referenceBean.get();
    }


    /**
     * Generate a cache key of {@link ReferenceBean}
     *
     * @param dubboReference {@link DubboReference}
     * @param beanClass {@link Class}
     * @return
     */
    private static String generateReferenceBeanCacheKey(DubboReference dubboReference, Class<?> beanClass) {

        String interfaceName = resolveInterfaceName(dubboReference, beanClass);

        String key = dubboReference.group() + "/" + interfaceName + ":" + dubboReference.version();

        return key;

    }

    private static String resolveInterfaceName(DubboReference dubboReference, Class<?> beanClass)
            throws IllegalStateException {

        String interfaceName;
        if (!"".equals(dubboReference.interfaceName())) {
            interfaceName = dubboReference.interfaceName();
        } else if (!void.class.equals(dubboReference.interfaceClass())) {
            interfaceName = dubboReference.interfaceClass().getName();
        } else if (beanClass.isInterface()) {
            interfaceName = beanClass.getName();
        } else {
            throw new IllegalStateException(
                    "The @DubboReference undefined interfaceClass or interfaceName, and the property type "
                            + beanClass.getName() + " is not a interface.");
        }

        return interfaceName;

    }

}
