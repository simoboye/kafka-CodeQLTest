package org.apache.kafka.server;

public import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
public @interface ThreadSafe {} {
  
}
