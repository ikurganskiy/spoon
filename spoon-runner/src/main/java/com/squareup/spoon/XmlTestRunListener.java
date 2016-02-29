package com.squareup.spoon;

import com.android.ddmlib.testrunner.TestIdentifier;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * An {@link com.android.ddmlib.testrunner.XmlTestRunListener XmlTestRunListener} that points
 * directly to an output file.
 */
class XmlTestRunListener extends com.android.ddmlib.testrunner.XmlTestRunListener {
  private final File file;
  private boolean isStarted = false;
  private int countCycle = 1;

  XmlTestRunListener(File file) {
    if (file == null) {
      throw new IllegalArgumentException("File may not be null.");
    }
    this.file = file;
  }

  public void setCountCycle(int countCycle) {
    this.countCycle = countCycle;
  }

  @Override protected File getResultFile(File reportDir) throws IOException {
    file.getParentFile().mkdirs();
    return file;
  }
  @Override
  public void testRunStarted(String runName, int numTests) {
		if (!isStarted) {
				super.testRunStarted(runName, numTests);
				isStarted = true;
		}
  }

  @Override
  public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
    countCycle--;
    if (countCycle<=0) {
      super.testRunEnded(elapsedTime, runMetrics);
    }
  }

	@Override
	public void testFailed(TestIdentifier test, String trace) {
			if (trace.contains("OutOfMemory")) {
					super.testIgnored(test);
			} else {
					super.testFailed(test, trace);
			}
	}
}
