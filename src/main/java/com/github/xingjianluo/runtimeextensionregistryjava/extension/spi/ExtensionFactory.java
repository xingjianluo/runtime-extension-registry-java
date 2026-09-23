package com.github.xingjianluo.runtimeextensionregistryjava.extension.spi;

/** 扩展实例工厂，绑定在扩展定义上，用于按生效配置创建实例。 */
@FunctionalInterface
public interface ExtensionFactory {
    Extension create();
}
