# Escaping
State of field is escaping.


## Recommendation
Add a private modifier to the field declaration. If the state of the field must be exposed, consider creating synchronized getters and setters.


## Example
Here is an example of defining a field as a private method, and with getters and setters in a synchronized way.


```java
public class EscapingExample {
  private int x;
  
  public synchronized getX() {
    return x;
  }

  public synchronized setX(int x) {
    this.x = x;
  }
}

```

## References
* Java Doc: [Example of interference in JavaDoc](https://docs.oracle.com/javase/tutorial/essential/concurrency/interfere.html).
