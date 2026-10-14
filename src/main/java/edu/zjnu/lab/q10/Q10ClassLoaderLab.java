package edu.zjnu.lab.q10;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Q10 · 类加载 + 双亲委实验
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q10.Q10ClassLoaderLab
 *
 * 验证面试点:
 * 1. 委派顺序: 子加载器先问父, 父找不到才自己 findClass (打印真实委派链)
 * 2. 同一个类被不同加载器加载 = 两个不同的 Class (instanceof 都不认)
 * 3. 准备阶段赋"默认值", 初始化阶段才执行 <clinit> (static 赋值/静态块)
 */
public class Q10ClassLoaderLab {

    public static void main(String[] args) throws Exception {
        step1_delegateChain();
        step2_twoLoadersTwoClasses();
        step3_prepareVsInit();
    }

    // ========== 实验1: 看见双亲委派链 ==========

    static class VerboseLoader extends ClassLoader {
        VerboseLoader(ClassLoader parent) { super(parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            System.out.println("    [VerboseLoader] loadClass(" + name + ")");
            return super.loadClass(name, resolve); // 标准双亲委派: 先 parent.loadClass, 抛异常才轮到 findClass
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            System.out.println("    [VerboseLoader] findClass(" + name + ") <- 父加载器都没找到, 我自己读字节码");
            byte[] bytes = readClassBytes(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    static void step1_delegateChain() throws Exception {
        System.out.println("=== 实验1: 委派链 (自己写的类 + JDK 核心类各加载一次) ===");
        ClassLoader loader = new VerboseLoader(Q10ClassLoaderLab.class.getClassLoader());
        System.out.println("  加载自定义类 Demo:");
        Class<?> c = loader.loadClass("edu.zjnu.lab.q10.Demo");
        System.out.println("  Demo 的实际加载器 = " + c.getClassLoader() + "  (父加载器找到就用父的, 轮不到我)\n");
        System.out.println("  加载 java.lang.String:");
        Class<?> s = loader.loadClass("java.lang.String");
        System.out.println("  String 的实际加载器 = " + s.getClassLoader() + "  (null = Bootstrap, C++ 实现)");
        System.out.println("  >> 双亲委派两个目的: 核心 API 不被篡改 + 同名类只加载一份\n");
    }

    static byte[] readClassBytes(String className) {
        // 直接从 classpath 读编译产物 (target/classes), 模拟"从磁盘/网络读字节码"
        String path = "target/classes/" + className.replace('.', '/') + ".class";
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 实验2: 不同加载器加载同一个类 = 不同的 Class ==========

    static void step2_twoLoadersTwoClasses() throws Exception {
        System.out.println("=== 实验2: 两个自定义加载器各加载一次 Demo ===");
        ClassLoader parent = Q10ClassLoaderLab.class.getClassLoader().getParent(); // Ext/Platform
        Class<?> c1 = newLoader(parent).loadClass("edu.zjnu.lab.q10.Demo");
        Class<?> c2 = newLoader(parent).loadClass("edu.zjnu.lab.q10.Demo");
        System.out.println("  c1 == c2              : " + (c1 == c2));
        Object o1 = c1.newInstance(), o2 = c2.newInstance();
        System.out.println("  o1 instanceof c1      : " + c1.isInstance(o1));
        System.out.println("  o1 instanceof c2      : " + c2.isInstance(o1) + "  <-- 同名类, 加载器不同, 互不相认!");
        System.out.println("  >> 类的唯一标识 = 类加载器 + 全限定名 (这就是 Tomcat Webapp 隔离 / OSGi 的原理)\n");
    }

    static ClassLoader newLoader(ClassLoader parent) {
        return new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.")) return super.loadClass(name, resolve); // 核心类必须委派
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        try { // 破坏双亲委派: 先自己 findClass, 找不到再问父 —— Tomcat WebAppClassLoader 的路数
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            c = super.loadClass(name, false);
                        }
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = readClassBytes(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    // ========== 实验3: 准备阶段(默认值) vs 初始化(<clinit>) ==========

    static void step3_prepareVsInit() throws Exception {
        System.out.println("=== 实验3: 类加载各阶段对 static 变量干什么 ===");
        ClassLoader parent = Q10ClassLoaderLab.class.getClassLoader().getParent();
        ClassLoader loader = newLoader(parent);
        System.out.println("  (1) loadClass 完成 = 加载+验证+准备+解析, 但【不初始化】:");
        Class<?> c = loader.loadClass("edu.zjnu.lab.q10.InitOrderDemo");
        System.out.println("      静态块执行了吗? 还没输出 -> 否. 此时 VALUE 处于准备阶段的默认值 0");
        System.out.println("  (2) new 实例 = 主动引用, 触发初始化, 执行 <clinit>:");
        Object o = c.newInstance();
        System.out.println("      通过反射读 VALUE = " + c.getField("VALUE").get(null));
        System.out.println("  >> 准备阶段: static int VALUE = 42 先给 0; 初始化阶段: 执行<clinit>才赋 42");
        System.out.println("  >> 面试易错点: '准备阶段赋初始值'错! 赋的是【默认值】(0/null), 代码里的值在初始化阶段\n");
    }
}
