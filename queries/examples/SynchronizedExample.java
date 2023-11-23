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
