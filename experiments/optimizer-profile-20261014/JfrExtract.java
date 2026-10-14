import java.nio.file.*;
import java.io.*;
import java.util.*;
import jdk.jfr.consumer.*;

/** Read-only exporter for built-in JFR events; never loaded into the measured JVM. */
public class JfrExtract {
  static final Set<String> TYPES = Set.of("jdk.ExecutionSample", "jdk.NativeMethodSample",
    "jdk.ObjectAllocationSample", "jdk.CPULoad", "jdk.ThreadCPULoad", "jdk.GCHeapSummary",
    "jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.ThreadAllocationStatistics",
    "jdk.PhysicalMemory", "jdk.CPUInformation", "jdk.DataLoss");
  static String q(String s) {
    if(s==null) return "null";
    return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
  }
  static String value(Object v) {
    if(v instanceof RecordedClass c) return q(c.getName());
    if(v instanceof Number || v instanceof Boolean) return v.toString();
    return q(String.valueOf(v));
  }
  public static void main(String[] args) throws Exception {
    try(RecordingFile file=new RecordingFile(Path.of(args[0]));
        PrintWriter out=new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
      while(file.hasMoreEvents()) {
        RecordedEvent e=file.readEvent(); String type=e.getEventType().getName();
        if(!TYPES.contains(type)) continue;
        StringBuilder s=new StringBuilder("{\"type\":").append(q(type))
          .append(",\"time\":").append(q(e.getStartTime().toString()))
          .append(",\"epoch\":").append(e.getStartTime().getEpochSecond()+e.getStartTime().getNano()/1e9)
          .append(",\"durationSeconds\":").append(e.getDuration().toNanos()/1e9);
        RecordedThread t=e.hasField("sampledThread") ? e.getThread("sampledThread")
          : e.hasField("thread") ? e.getThread("thread") : e.getThread();
        if(t!=null) s.append(",\"thread\":").append(q(t.getJavaName())).append(",\"threadId\":").append(t.getJavaThreadId());
        for(String field:List.of("weight","objectClass","jvmUser","jvmSystem","machineTotal","user","system",
          "heapUsed","when","gcId","name","cause","sumOfPauses","longestPause","allocated","usedSize",
          "totalSize","hwThreads","cores","sockets","description","amount","total"))
          if(e.hasField(field)) s.append(',').append(q(field)).append(':').append(value(e.getValue(field)));
        RecordedStackTrace stack=e.getStackTrace();
        if(stack!=null) {
          s.append(",\"truncated\":").append(stack.isTruncated()).append(",\"stack\":[");
          boolean first=true;
          for(RecordedFrame f:stack.getFrames()) {
            if(!first) s.append(','); first=false;
            s.append(q(f.getMethod().getType().getName()+"."+f.getMethod().getName()+":"+f.getLineNumber()));
          }
          s.append(']');
        }
        out.println(s.append('}'));
      }
    }
  }
}
