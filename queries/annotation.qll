import java

predicate isElementInThreadSafeAnnotatedClass(Class c, Element e) {
  c.getAnAnnotation().toString() = "ThreadSafe"
  and c.contains(e)
}
