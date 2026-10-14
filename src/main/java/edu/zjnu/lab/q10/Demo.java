package edu.zjnu.lab.q10;

/** 被 Q10 实验用自定义加载器从磁盘加载的目标类 */
public class Demo {
    static {
        System.out.println("    Demo.<clinit> 执行: 我被 " + Demo.class.getClassLoader() + " 初始化了");
    }
}
