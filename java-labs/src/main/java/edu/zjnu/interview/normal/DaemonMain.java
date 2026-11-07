package edu.zjnu.interview.normal;


/**
 * todo
 *
 * @Date 2026-10-25 11:00
 * @Author 杨海波
 **/
public class DaemonMain extends Thread {


    @Override
    public void run() {
        System.out.println(Thread.currentThread().getId());
        System.out.println(Thread.currentThread().getPriority());
        System.out.println(Thread.currentThread().getState());
        System.out.println("Daemon run");
    }

    public static void main(String[] args) {

        Thread t =  new Thread(new DaemonMain());
        t.setDaemon(true);
        t.start();
        System.out.println("Main run");
    }

}
