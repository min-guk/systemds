import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public final class SingleMethodRunner {
  public static void main(String[] args) throws Exception {
    Class<?> testClass = Class.forName(args[0]);
    Result result = new JUnitCore().run(Request.method(testClass, args[1]));
    for (Failure failure : result.getFailures())
      System.out.println(failure.toString() + "\n" + failure.getTrace());
    System.out.println("SINGLE_METHOD_RESULT run=" + result.getRunCount()
      + " failures=" + result.getFailureCount() + " ignored=" + result.getIgnoreCount()
      + " runtimeMillis=" + result.getRunTime());
    if (!result.wasSuccessful()) System.exit(1);
  }
}
