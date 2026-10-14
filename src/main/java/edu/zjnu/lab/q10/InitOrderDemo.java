package edu.zjnu.lab.q10;

/** 演示准备阶段(默认值) vs 初始化阶段(<clinit>) */
public class InitOrderDemo {
    public static final int VALUE = 42;

    static {
        System.out.println("      InitOrderDemo.<clinit> 执行! (static 块 + static 赋值在这一步)");
    }
}
