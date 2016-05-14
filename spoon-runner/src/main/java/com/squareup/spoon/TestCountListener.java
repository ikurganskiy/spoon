package com.squareup.spoon;

import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.TestIdentifier;

import java.util.Map;
import java.util.concurrent.CountDownLatch;


public class TestCountListener implements ITestRunListener {
  private int count;
  private CountDownLatch countDownLatch = new CountDownLatch(1);

  @Override
  public void testRunStarted(String runName, int testCount) {
    count = testCount;
    countDownLatch.countDown();
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

  }

  public int getCount() {
    try {
      countDownLatch.await();
      return count;
    } catch (InterruptedException e) {
      return 0;
    }
  }
}
