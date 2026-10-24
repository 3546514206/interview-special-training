package edu.zjnu.interview.normal;


/**
 * RunnableMain
 *
 * @Date 2026-10-25 00:52
 * @Author 杨海波
 **/
public class RunnableMain implements Runnable {

    @Override
    public void run() {
        Thread currentThread = Thread.currentThread();
        System.out.println(currentThread.getName());
    }

    public static void main(String[] args) {
        RunnableMain a = new RunnableMain();
        Thread t1 = new Thread(a);
        t1.start();
    }
}
