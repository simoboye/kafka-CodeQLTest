/**
 * @name Escaping
 * @kind problem
 * @problem.severity warning
 * @id java/escaping
 */

import java
import annotation

predicate isNotPrivate(Field f) {
  not f.isPrivate()
}

from Field f, Class c
where isElementInThreadSafeAnnotatedClass(c, f) 
  and isNotPrivate(f)
select f, "Potentially escaping field"
