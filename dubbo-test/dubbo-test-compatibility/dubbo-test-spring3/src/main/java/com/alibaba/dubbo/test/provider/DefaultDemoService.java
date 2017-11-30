package com.alibaba.dubbo.test.provider;

import com.alibaba.dubbo.config.annotation.DubboService;
import com.alibaba.dubbo.demo.DemoService;

/**
 * Default {@link DemoService} implementation
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 * @since 2.5.8
 */
@DubboService(
        version = "2.5.8",
        application = "dubbo-annotation-provider",
        protocol = "dubbo",
        registry = "my-registry"
)
public class DefaultDemoService implements DemoService {

    @Override
    public String sayHello(String name) {
        return "DefaultDemoService - sayHell() : " + name;
    }

}
