package com.squareup.spoon;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.TestIdentifier;
import com.squareup.spoon.model.Device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.squareup.spoon.ExecutionSummary.DISPLAY_TIME;
import static com.squareup.spoon.ExecutionTestResult.TestResult.FAILURE;
import static com.squareup.spoon.ExecutionTestResult.TestResult.SUCCESS;

/** Represents the aggregated result of a test execution on a device. */
public class ExecutionResult implements ITestRunListener {
  public Device device;
  public int testsStarted;
  public int testsFailed;
  public int testsPassed;
  public String deviceName;
  public String deviceManufacturer;
  public String deviceVersion;
  public String deviceApiLevel;
  public String deviceLanguage;
  public String deviceRegion;
  public long testStart;
  public long testEnd;
  public long totalTime;
  public String displayTime;
  private final Map<String, ExecutionTestResult> testResults = new HashMap<String, ExecutionTestResult>();

  public ExecutionResult() {
    //Used for Jackson
  }
  public ExecutionResult(Device device) {
    this.device = device;
  }

  @Override public void testRunStarted(String runName, int testCount) {
    System.out.println("[testRunStarted] runName: " + runName + ", " + testCount);
  }

  @Override public void testStarted(TestIdentifier test) {
    System.out.println("[testStarted] test: " + test);
    testResults.put(test.toString(), new ExecutionTestResult(test));
    testsStarted += 1;
  }

  @Override public void testFailed(TestFailure status, TestIdentifier test, String trace) {
    System.out.println("[testFailed] status: " + status + ", test: " + test + ", trace: " + trace);
    testResults.get(test.toString()).result = FAILURE;
    testsFailed += 1;
  }

  @Override public void testEnded(TestIdentifier test, Map<String, String> testMetrics) {
    System.out.println("[testEnded] test: " + test + ", testMetrics: " + testMetrics);
    final ExecutionTestResult testResult = testResults.get(test.toString());
    if (testResult.result == null) {
      testResult.result = SUCCESS;
      testsPassed += 1;
    }
  }

  @Override public void testRunFailed(String errorMessage) {
    System.out.println("[testRunFailed] errorMessage: " + errorMessage);
  }

  @Override public void testRunStopped(long elapsedTime) {
    System.out.println("[testRunStopped] elapsedTime: " + elapsedTime);
  }

  @Override public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
    System.out.println("[testRunEnded] elapsedTime: " + elapsedTime + ", runMetrics: " + runMetrics);

  }

  /** Mustache can't read maps. Feed it a list to consume. Nom nom nom. */
  public List<ExecutionTestResult> tests() {
    List<ExecutionTestResult> tests = new ArrayList<ExecutionTestResult>(testResults.values());
    Collections.sort(tests, new Comparator<ExecutionTestResult>() {
      @Override public int compare(ExecutionTestResult executionTestResult, ExecutionTestResult executionTestResult1) {
        int className = executionTestResult.className.compareTo(executionTestResult1.className);
        if (className != 0) {
          return className;
        }
        return executionTestResult.testName.compareTo(executionTestResult1.testName);
      }
    });
    return tests;
  }

  public void configureFor(IDevice realDevice) {
    this.deviceName = realDevice.getProperty("ro.product.model");
    this.deviceManufacturer = realDevice.getProperty("ro.product.manufacturer");
    this.deviceVersion = realDevice.getProperty("ro.build.version.release");
    this.deviceApiLevel = realDevice.getProperty("ro.build.version.sdk");
    this.deviceLanguage = realDevice.getProperty("ro.product.locale.language");
    this.deviceRegion = realDevice.getProperty("ro.product.locale.region");
  }

  public void updateDynamicValues() {
    totalTime = TimeUnit.MILLISECONDS.toSeconds(testEnd - testStart);
    displayTime = DISPLAY_TIME.get().format(new Date(testEnd));
  }
}
