import java

predicate isFieldInThreadSafeAnnotatedClass(Class c, Field f) {
  c.getAnAnnotation().toString() = "ThreadSafe"
  and c.declaresField(f.getName())
}

predicate isMethodInThreadSafeAnnotatedClass(Class c, Method m) {
  c.getAnAnnotation().toString() = "ThreadSafe"
  and c.declaresMethod(m.getName()) // c.contains(ca)
}
