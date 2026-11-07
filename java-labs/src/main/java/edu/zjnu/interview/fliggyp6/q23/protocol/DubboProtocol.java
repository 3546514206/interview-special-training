package edu.zjnu.interview.fliggyp6.q23.protocol;

/** dubbo 协议实现 (Netty 长连接) */
public class DubboProtocol implements Protocol {
    public DubboProtocol() { System.out.println("    [实例化] DubboProtocol (按需创建, 不是加载 SPI 时全量 new)"); }

    @Override
    public String export(String url) { return "Dubbo协议导出: " + url; }
}
