package edu.zjnu.interview.normal;


/**
 * todo
 *
 * @Date 2026-10-25 18:51
 * @Author 杨海波
 **/
public class ThreadJoinMain {

    public static void main(String[] args) {
        Thread t1 = new T5();
        t1.start();

        Thread t2 = new T5();
        t2.start();

        try {
            t1.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        System.out.println("main");
    }
}

class T5 extends Thread{
    @Override
    public void run() {
        System.out.println("T5");
    }
}
