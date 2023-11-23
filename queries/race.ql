import java

// from ClassInstanceExpr ci 
// where ci.getType().hasName("Thread") 


// select ci.getAChildExpr().getAChildExpr()

from Callable c, MethodAccess ma
where 
  ma.getCaller().hasName("run")
  // and ma.getEnclosingCallable()
  // and ma.getMethod() = c
  // and c.hasName("lock")
  and (c.hasName("lock") and ma.getMethod().calls(c))
select ma, c

// from MethodAccess ma, Method m
// where ma.getCaller().hasName("run") and m.calls(ma.getCallee())
// select ma //, ma.getCallee().getBody().getAChild(), ma.getReceiverType()

// from Class c

// where c.getASupertype().hasName("Thread") -- Only supports directly extend of Thread

// select c


