package queries;

public class CodeExamples {
  // Escaping - potentially exposes the state of the field.
  int esc;
  
  // Safe-publication: The field is not properly initialized in the constructor or field initializer.
  private String publication;
  
  private int immutableField = 0;
  private int mutableField = 0;

  public CodeExamples() {}

  public int getImmutableField() {
    // Immutable field does not need to be in a happens-before relation.
    return immutableField;
  }

  public synchronized void setMutableField(int mutableField) {
    // Mutable field is protected by the implicit lock on the 'this' object, visible by the synchronized keyword.
    this.mutableField = mutableField;
  }
}
