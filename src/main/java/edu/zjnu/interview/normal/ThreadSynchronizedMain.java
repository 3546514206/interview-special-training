package edu.zjnu.interview.normal;


/**
 * todo
 *
 * @Date 2026-10-26 23:16
 * @Author 杨海波
 **/
public class ThreadSynchronizedMain {

    private int count = 0;

    // synchronized 修饰实例方法，锁的是 this
    public synchronized void increment() {
        count++;
    }

    public synchronized int getCount() {
        return count;
    }

    public static void main(String[] args) throws InterruptedException {
        ThreadSynchronizedMain threadSynchronizedMain = new ThreadSynchronizedMain();

        // 创建两个线程，各执行 10000 次 increment
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 10000; i++) {
                threadSynchronizedMain.increment();
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 10000; i++) {
                threadSynchronizedMain.increment();
            }
        });

        t1.start();
        t2.start();

        // 等两个线程都执行完
        t1.join();
        t2.join();

        System.out.println("最终 count = " + threadSynchronizedMain.getCount());
        System.out.println("期望       = 20000");
    }
}