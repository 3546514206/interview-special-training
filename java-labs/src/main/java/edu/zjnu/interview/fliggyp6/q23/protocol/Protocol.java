package edu.zjnu.interview.fliggyp6.q23.protocol;

/**
 * SPI 接口, 模拟 Dubbo 的 Protocol (真实: @SPI("dubbo") 指定默认实现)
 */
public interface Protocol {
    String export(String url);
}
