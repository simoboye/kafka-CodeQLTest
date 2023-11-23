public class EscapingExample {
  private int x;
  
  public synchronized getX() {
    return x;
  }

  public synchronized setX(int x) {
    this.x = x;
  }
}
