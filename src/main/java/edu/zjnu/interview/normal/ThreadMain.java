package edu.zjnu.interview.normal;


/**
 * ThreadMain
 *
 * @Date 2026-10-25 01:27
 * @Author 杨海波
 **/
public class ThreadMain extends Thread {

    @Override
    public void run() {
        super.run();
        System.out.println("你好");
    }


    public static void main(String[] args) {
        Thread t = new ThreadMain();
        t.start();
    }
}
