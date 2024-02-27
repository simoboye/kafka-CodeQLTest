import java
import semmle.code.java.Concurrency
import annotation

from Class c, Method m, SynchronizedStmt s
where 
  isElementInThreadSafeAnnotatedClass(c, m)
  // and not m.hasName("<obinit>")
  // and m.accesses(f)
select s, s.getBlock(), s.getBasicBlock(), s.getBasicBlock().getEnclosingStmt()
