package edu.zjnu.interview.normal;


/**
 * todo
 *
 * @Date 2026-10-25 11:07
 * @Author 杨海波
 **/
public class ThreadTestMain {

    public static void main(String[] args) {
        new Thread(()->{
            System.out.println("main");
        }).start();
    }
}
