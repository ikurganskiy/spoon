package com.squareup.spoon;

import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.TestIdentifier;

import java.util.Map;

public class PullDeviceFilesListener implements ITestRunListener {
  private int testCount;
  private final PullDeviceListener listener;

  public PullDeviceFilesListener(PullDeviceListener listener) {
    this.listener = listener;
  }

  @Override
  public void testRunStarted(String runName, int testCount) {
    this.testCount = testCount;
  }

  @Override
  public void testStarted(TestIdentifier test) {

  }

  @Override
  public void testFailed(TestIdentifier test, String trace) {

  }

  @Override
  public void testAssumptionFailure(TestIdentifier test, String trace) {

  }

  @Override
  public void testIgnored(TestIdentifier test) {

  }

  @Override
  public void testEnded(TestIdentifier test, Map<String, String> testMetrics) {

  }

  @Override
  public void testRunFailed(String errorMessage) {

  }

  @Override
  public void testRunStopped(long elapsedTime) {

  }

  @Override
  public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
    if (testCount > 0 && listener != null) {
      listener.pullDeviceFiles();
    }
  }

  public interface PullDeviceListener {
    void pullDeviceFiles();
  }
}
