package com.alibaba.dubbo.config.spring.beans.factory.annotation;

import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.annotation.DubboReference;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import org.springframework.context.ApplicationContext;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import static com.alibaba.dubbo.config.spring.util.BeanFactoryUtils.getOptionalBean;

/**
 * {@link ReferenceBean} Builder
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 * @since 2.5.7
 */
class ReferenceBeanBuilder extends AbstractAnnotationConfigBeanBuilder<DubboReference, ReferenceBean> {


    private ReferenceBeanBuilder(DubboReference annotation, ClassLoader classLoader, ApplicationContext applicationContext) {
        super(annotation, classLoader, applicationContext);
    }

    private void configureInterface(DubboReference dubboReference, ReferenceBean referenceBean) {

        Class<?> interfaceClass = dubboReference.interfaceClass();

        if (void.class.equals(interfaceClass)) {

            interfaceClass = null;

            String interfaceClassName = dubboReference.interfaceName();

            if (StringUtils.hasText(interfaceClassName)) {
                if (ClassUtils.isPresent(interfaceClassName, classLoader)) {
                    interfaceClass = ClassUtils.resolveClassName(interfaceClassName, classLoader);
                }
            }

        }

        if (interfaceClass == null) {
            interfaceClass = this.interfaceClass;
        }

        Assert.isTrue(interfaceClass.isInterface(),
                "The class of field or method that was annotated @DubboReference is not an interface!");

        referenceBean.setInterface(interfaceClass);

    }


    private void configureConsumerConfig(DubboReference dubboReference, ReferenceBean<?> referenceBean) {

        String consumerBeanName = dubboReference.consumer();

        ConsumerConfig consumerConfig = getOptionalBean(applicationContext, consumerBeanName, ConsumerConfig.class);

        referenceBean.setConsumer(consumerConfig);

    }

    @Override
    protected ReferenceBean doBuild() {
        return new ReferenceBean<Object>(annotation);
    }

    @Override
    protected void preConfigureBean(DubboReference annotation, ReferenceBean bean) {
        Assert.notNull(interfaceClass, "The interface class must set first!");
    }

    @Override
    protected String resolveModuleConfigBeanName(DubboReference annotation) {
        return annotation.module();
    }

    @Override
    protected String resolveApplicationConfigBeanName(DubboReference annotation) {
        return annotation.application();
    }

    @Override
    protected String[] resolveRegistryConfigBeanNames(DubboReference annotation) {
        return annotation.registry();
    }

    @Override
    protected String resolveMonitorConfigBeanName(DubboReference annotation) {
        return annotation.monitor();
    }

    @Override
    protected void postConfigureBean(DubboReference annotation, ReferenceBean bean) throws Exception {

        bean.setApplicationContext(applicationContext);

        configureInterface(annotation, bean);

        configureConsumerConfig(annotation, bean);

        bean.afterPropertiesSet();

    }

    public static ReferenceBeanBuilder create(DubboReference annotation, ClassLoader classLoader,
                                              ApplicationContext applicationContext) {
        return new ReferenceBeanBuilder(annotation, classLoader, applicationContext);
    }

}
