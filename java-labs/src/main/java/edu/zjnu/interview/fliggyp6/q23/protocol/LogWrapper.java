package edu.zjnu.interview.fliggyp6.q23.protocol;

/**
 * Wrapper 特征: 构造器参数是【本接口】类型 -> Dubbo 自动识别为包装类, 像 AOP 一样层层包裹
 */
public class LogWrapper implements Protocol {
    private final Protocol inner;

    public LogWrapper(Protocol inner) {
        this.inner = inner;
        System.out.println("    [Wrapper识别] 构造器带 Protocol 参数 -> 自动包裹一层日志能力");
    }

    @Override
    public String export(String url) {
        System.out.println("    [LogWrapper] 调用前打日志 (等价 AOP @Before)");
        try {
            return inner.export(url);
        } finally {
            System.out.println("    [LogWrapper] 调用后打日志 (等价 AOP @After)");
        }
    }
}
