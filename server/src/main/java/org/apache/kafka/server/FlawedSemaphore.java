// package org.apache.kafka.server;

// @ThreadSafe
// public class FlawedSemaphore {
//   private final int capacity;
//   private int state;

//   public FlawedSemaphore(int c) {
//     capacity = c;
//     state = 0;
//   }

//   public void acquire() {
//     try {
//       while (state == capacity) {
//         this.wait();
//       }
//       state++;
//     } catch (InterruptedException e) {
//       e.printStackTrace();
//     }
//   }

//   public void release() {
//     synchronized (this) {
//       state--; // State can become negative
//       this.notifyAll();
//     }
//   }
// }