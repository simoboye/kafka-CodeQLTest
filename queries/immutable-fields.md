# Immutable field
Immutable field is accessed in a syncronzied block.


## Recommendation
An immutable field doesn't need to be referenced in a synchronized way. If there is no other reason to do so, then consider removing the syncronization.


## Example
An example of an immutable field without the need of syncronization.


```java
public class ImmutableFieldExample {
  private int x = 0;
  
  public getX() {
    return x;
  }
}

```

## References
* Java Doc: [A strategy for defining immutable objects from JavaDoc](https://docs.oracle.com/javase/tutorial/essential/concurrency/imstrat.html).
