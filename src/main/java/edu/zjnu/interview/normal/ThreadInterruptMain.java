package edu.zjnu.interview.normal;


/**
 * todo
 *
 * @Date 2026-10-25 20:07
 * @Author 杨海波
 **/
public class ThreadInterruptMain {

    public static void main(String[] args) throws InterruptedException {
        Thread thread = new ThreadInterrupt();
        thread.start();

        Thread.sleep(3000);
        thread.interrupt();
    }
}

class ThreadInterrupt extends Thread {

    @Override
    public void run() {
        while (true) {
            System.out.println("main");
            if (this.isInterrupted()) {
                System.out.println("isInterrupted");
                break;
            }
        }
    }
}