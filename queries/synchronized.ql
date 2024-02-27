/**
 * @name Synchronized
 * @kind problem
 * @problem.severity warning
 * @id java/synchronized
 */

import java
import annotation
import semmle.code.java.Concurrency

predicate fieldAccessNotInsideStmt(Location stmtLocation, FieldAccess fa) {
  not (stmtLocation.getStartLine() < fa.getLocation().getStartLine() and
      stmtLocation.getEndLine() > fa.getLocation().getStartLine())  
    and 
  not (stmtLocation.getStartColumn() < fa.getLocation().getStartColumn() and
    stmtLocation.getEndColumn() > fa.getLocation().getStartColumn()) 
}

predicate hasNoSynchronizedThis(Callable ca, FieldAccess fa) {
  not ca.isSynchronized()
  and
  // Method calls should be like a write -> this is the case in synchronized query. 
  (
    not exists(SynchronizedStmt s | s.getEnclosingCallable() = ca | s.getExpr().(ThisAccess).getType() = ca.getDeclaringType())
      or
    exists(SynchronizedStmt s | s.getEnclosingCallable() = ca | fieldAccessNotInsideStmt(s.getBlock().getLocation(), fa))
  )
}

predicate hasNoLocks(FieldAccess fa) {
  not exists(
    MethodAccess ma | 
    ma.getMethod().hasName("lock") and ma.getEnclosingCallable() = fa.getEnclosingCallable() or
    ma.getMethod().hasName("unlock") and ma.getEnclosingCallable() = fa.getEnclosingCallable()
  )
}

predicate hasUnlockButNoLock(FieldAccess fa){
  exists(
    MethodAccess ma | 
    ma.getMethod().hasName("unlock") and ma.getEnclosingCallable() = fa.getEnclosingCallable() |
    not exists( MethodAccess ma2 | 
            ma2.getMethod().hasName("lock") and ma2.getEnclosingCallable() = fa.getEnclosingCallable() and
            ma.getQualifier().toString() = ma2.getQualifier().toString())
  )
}

predicate hasLockButNoUnlock(FieldAccess fa){
  exists(
    MethodAccess ma | 
    ma.getMethod().hasName("lock") and ma.getEnclosingCallable() = fa.getEnclosingCallable() |
    not exists(MethodAccess ma2 | 
            ma2.getMethod().hasName("unlock") and ma2.getEnclosingCallable() = fa.getEnclosingCallable() and
            ma.getQualifier().toString() = ma2.getQualifier().toString())
  )
}

predicate checkLocks(FieldAccess fa) {
  // Assumptions: One should always lock and unlock in the same callable.

  // We do not find these: fieldUpdateOutsideOfLock: this is due to the location... // Bjørnar
  // and "notTheSameLockAsAdd" this due to related fields in different method with different locks // Simon

  hasNoLocks(fa)
    or
  hasLockButNoUnlock(fa)
    or
  hasUnlockButNoLock(fa)
    or
  checkIfAllFieldsAreInLock(fa)
}

predicate checkIfAllFieldsAreInLock(FieldAccess fa){
  exists(
    MethodAccess ma | 
    ma.getMethod().hasName("lock") and ma.getEnclosingCallable() = fa.getEnclosingCallable() |
    ma.getLocation().getStartLine() > fa.getLocation().getStartLine()
    // and (ma.getLocation().getStartColumn() > fa.getLocation().getStartColumn())
  )
  or 
  exists(
    MethodAccess ma | 
    ma.getMethod().hasName("unlock") and ma.getEnclosingCallable() = fa.getEnclosingCallable() |
    ma.getLocation().getEndLine() < fa.getLocation().getStartLine()
    // and (ma.getLocation().getEndColumn() < fa.getLocation().getStartColumn())
  )
}

from Class c, Method m, FieldAccess fa
where 
  isElementInThreadSafeAnnotatedClass(c, m)
  and not m.hasName("<obinit>")
  and fa.getEnclosingCallable() = m
  and not m.isPrivate() // Should we have this as a recursive problem or just report the private method?
  and hasNoSynchronizedThis(m, fa)
  and checkLocks(fa)
select m, "Writes to a field. Consider it being in a synchronized block."
