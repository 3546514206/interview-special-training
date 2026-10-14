package edu.zjnu.lab.q23.protocol;

/** http 协议实现 */
public class HttpProtocol implements Protocol {
    public HttpProtocol() { System.out.println("    [实例化] HttpProtocol"); }

    @Override
    public String export(String url) { return "Http协议导出: " + url; }
}
