import java
import semmle.code.java.Concurrency

from Method m//, SynchronizedCallable sc
// where locallySynchronizedOn(m, sync, v)
select m
