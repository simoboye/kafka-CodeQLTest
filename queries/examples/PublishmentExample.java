package queries.examples;

public class PublishmentExample {
    private final int finalField;
    private volatile int volatileField;
  
    public PublishmentExample(int c) {
      finalField = c;
      volatileField = c;
    }
}
