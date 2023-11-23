/**
 * @name Publishment
 * @kind problem
 * @problem.severity error
 * @id java/publishment
 */

import java
import annotation

predicate isNotSafelyPublished(Field f) {
  not (f.isFinal() or isDefaultValue(f)) 
}

// What about other datatypes
// https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html -- See default values
// And what about arrays?
predicate isDefaultValue(Field f) {
  (f.getType().hasName("int") and f.getAnAssignedValue().toString() = "0")
  or (f.getType().hasName("boolean") and f.getAnAssignedValue().toString() = "false")  
  or f.getAnAssignedValue().toString() = "null"
}

from Field f, Class c
where isFieldInThreadSafeAnnotatedClass(c, f)
and isNotSafelyPublished(f)
select f, "Is not safely published"
