# Publishment
The field might not safe published.

For a field to be safely published, the Initialization must happen before the publication of the field.

All the field in an object must be correctly initialized before it is made accessible as a reference to an object.


## Recommendation
Make the fields volatile or final.


## Example
Here is an example of safe publishment


```java
package queries.examples;

public class PublishmentExample {
    private final int finalField;
    private volatile int volatileField;
  
    public PublishmentExample(int c) {
      finalField = c;
      volatileField = c;
    }
}

```

## References
* Safe Publication and Safe Initialization in Java: [More about safe publicaiton by Aleksey Shipilёv](https://shipilev.net/blog/2014/safe-public-construction/).
