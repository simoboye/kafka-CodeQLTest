# Synchronized
Here, it is written to a mutable field, and it is not inside of a synchronized block. This can cause problems with synchronization.


## Recommendation
Consider the field write to be inside of a synchronized block.


## Example
Here is an example of using the synchronized keyword


```java
package queries.examples;

public class SynchronizedExample {
    private int mutableField;

    public synchronized void increaseMutableField() {
        mutableField = mutableField + 1;
    }
    
    public synchronized int getAndIncreaseMutableField() {
        mutableField = mutableField + 1;
        return mutableField;
    }
}

```

## References
* Java Doc: [More about synchronized in JavaDoc](https://docs.oracle.com/javase/tutorial/essential/concurrency/syncmeth.html).
