package com.squareup.spoon;

import com.android.ddmlib.*;
import com.android.ddmlib.logcat.LogCatMessage;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.squareup.spoon.adapters.TestIdentifierAdapter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.*;
import java.util.*;

import static com.android.ddmlib.FileListingService.FileEntry;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.squareup.spoon.Spoon.SPOON_FILES;
import static com.squareup.spoon.Spoon.SPOON_SCREENSHOTS;
import static com.squareup.spoon.SpoonLogger.*;
import static com.squareup.spoon.SpoonUtils.*;

/**
 * Represents a single device and the test configuration to be executed.
 */
public final class SpoonDeviceRunner {
  private static final String FILE_EXECUTION = "execution.json";
  private static final String FILE_RESULT = "result.json";
  private static final String DEVICE_SCREENSHOT_DIR = "app_" + SPOON_SCREENSHOTS;
  private static final String DEVICE_FILE_DIR = "app_" + SPOON_FILES;
  private static final String[] DEVICE_DIRS = {DEVICE_SCREENSHOT_DIR, DEVICE_FILE_DIR};
  static final String TEMP_DIR = "work";
  static final String JUNIT_DIR = "junit-reports";
  static final String IMAGE_DIR = "image";
  static final String FILE_DIR = "file";

  private static final String SMALL = "android.test.suitebuilder.annotation.SmallTest";
  private static final String MEDIUM = "android.test.suitebuilder.annotation.MediumTest";
  private static final String LARGE = "android.test.suitebuilder.annotation.LargeTest";
  private static final String SMOKE = "android.test.suitebuilder.annotation.Smoke";

  private final File sdk;
  private final File apk;
  private final File testApk;
  private final String serial;
  private final boolean debug;
  private final boolean noAnimations;
  private final int adbTimeout;
  private final List<String> instrumentationArgs;
  private final int testCountInOneProcess;
  private final String className;
  private final String methodName;
  private final String testSize;
  private final File work;
  private final File junitReport;
  private final File imageDir;
  private final File fileDir;
  private final String classpath;
  private final SpoonInstrumentationInfo instrumentationInfo;
  private final List<ITestRunListener> testRunListeners;

  /**
   * Create a test runner for a single device.
   *
   * @param sdk                 Path to the local Android SDK directory.
   * @param apk                 Path to application APK.
   * @param testApk             Path to test application APK.
   * @param output              Path to output directory.
   * @param serial              Device to run the test on.
   * @param debug               Whether or not debug logging is enabled.
   * @param adbTimeout          time in ms for longest test execution
   * @param classpath           Custom JVM classpath or {@code null}.
   * @param instrumentationInfo Test apk manifest information.
   * @param className           Test class name to run or {@code null} to run all tests.
   * @param methodName          Test method name to run or {@code null} to run all tests.  Must also pass
   *                            {@code className}.
   * @param testRunListeners    Additional TestRunListener or empty list.
   */
  SpoonDeviceRunner(File sdk, File apk, File testApk, File output, String serial, boolean debug,
                    boolean noAnimations, int adbTimeout, String classpath,
                    SpoonInstrumentationInfo instrumentationInfo, List<String> instrumentationArgs,
                    String className, String methodName, String testSize,
                    List<ITestRunListener> testRunListeners, int testCountInOneProcess) {
    this.sdk = sdk;
    this.apk = apk;
    this.testApk = testApk;
    this.serial = serial;
    this.debug = debug;
    this.noAnimations = noAnimations;
    this.adbTimeout = adbTimeout;
    this.instrumentationArgs = instrumentationArgs;
    this.className = className;
    this.methodName = methodName;
    this.testCountInOneProcess = testCountInOneProcess;
    this.testSize = testSize;
    this.classpath = classpath;
    this.instrumentationInfo = instrumentationInfo;
    serial = SpoonUtils.sanitizeSerial(serial);
    this.work = FileUtils.getFile(output, TEMP_DIR, serial);
    this.junitReport = FileUtils.getFile(output, JUNIT_DIR, serial + ".xml");
    this.imageDir = FileUtils.getFile(output, IMAGE_DIR, serial);
    this.fileDir = FileUtils.getFile(output, FILE_DIR, serial);
    this.testRunListeners = testRunListeners;
  }

  /**
   * Serialize to disk and start {@link #main(String...)} in another process.
   */
  public DeviceResult runInNewProcess() throws IOException, InterruptedException {
    logDebug(debug, "[%s]", serial);

    // Create the output directory.
    work.mkdirs();

    // Write our configuration to a file in the output directory.
    FileWriter executionWriter = new FileWriter(new File(work, FILE_EXECUTION));
    GSON.toJson(this, executionWriter);
    executionWriter.close();

    // Kick off a new process to interface with ADB and perform the real execution.
    String name = SpoonDeviceRunner.class.getName();
    Process process = new ProcessBuilder("java", "-Djava.awt.headless=true", "-cp", classpath, name, work.getAbsolutePath()).start();
    printStream(process.getInputStream(), "STDOUT");
    printStream(process.getErrorStream(), "STDERR");

    final int exitCode = process.waitFor();
    logDebug(debug, "Process.waitFor() finished for [%s] with exitCode %d", serial, exitCode);

    // Read the result from a file in the output directory.
    FileReader resultFile = new FileReader(new File(work, FILE_RESULT));
    DeviceResult result = GSON.fromJson(resultFile, DeviceResult.class);
    resultFile.close();

    return result;
  }

  private void printStream(InputStream stream, String tag) throws IOException {
    BufferedReader stdout = new BufferedReader(new InputStreamReader(stream));
    String s;
    while ((s = stdout.readLine()) != null) {
      logDebug(debug, "[%s] %s %s", serial, tag, s);
    }
  }

  /**
   * Execute instrumentation on the target device and return a result summary.
   */
  public DeviceResult run(AndroidDebugBridge adb) {
    String testPackage = instrumentationInfo.getInstrumentationPackage();
    String testRunner = instrumentationInfo.getTestRunnerClass();
    TestIdentifierAdapter testIdentifierAdapter = TestIdentifierAdapter.fromTestRunner(testRunner);

    logDebug(debug, "InstrumentationInfo: [%s]", instrumentationInfo);

    if (debug) {
      SpoonUtils.setDdmlibInternalLoggingLevel();
    }

    DeviceResult.Builder result = new DeviceResult.Builder();

    final IDevice device = obtainRealDevice(adb, serial);
    logDebug(debug, "Got realDevice for [%s]", serial);

    // Get relevant device information.
    final DeviceDetails deviceDetails = DeviceDetails.createForDevice(device);
    result.setDeviceDetails(deviceDetails);
    logDebug(debug, "[%s] setDeviceDetails %s", serial, deviceDetails);

    DdmPreferences.setTimeOut(adbTimeout);

    // Now install the main application and the instrumentation application.
    try {
      device.installPackage(apk.getAbsolutePath(), true);
    } catch (InstallException e) {
      logInfo("InstallException while install app apk on device [%s]", serial);
      e.printStackTrace(System.out);
      return result.markInstallAsFailed("Unable to install application APK.").build();
    }
    try {
      device.installPackage(testApk.getAbsolutePath(), true);
    } catch (InstallException e) {
      logInfo("InstallException while install test apk on device [%s]", serial);
      e.printStackTrace(System.out);
      return result.markInstallAsFailed("Unable to install instrumentation APK.").build();
    }

    // If this is Android Marshmallow or above grant WRITE_EXTERNAL_STORAGE
    if (Integer.parseInt(device.getProperty(IDevice.PROP_BUILD_API_LEVEL)) >= 23) {
      String appPackage = instrumentationInfo.getApplicationPackage();
      try {
        CollectingOutputReceiver grantOutputReceiver = new CollectingOutputReceiver();
        device.executeShellCommand("pm grant " + appPackage + " android.permission.READ_EXTERNAL_STORAGE", grantOutputReceiver);
        device.executeShellCommand("pm grant " + appPackage + " android.permission.WRITE_EXTERNAL_STORAGE", grantOutputReceiver);
      } catch (Exception e) {
        logInfo("Exception while granting external storage access to application apk" + "on device [%s]", serial);
        e.printStackTrace(System.out);
        return result.markInstallAsFailed("Unable to grant external storage access to" + " application APK.").build();
      }
    }

    // Create the output directory, if it does not already exist.
    work.mkdirs();

    // Initiate device logging.
    SpoonDeviceLogger deviceLogger = new SpoonDeviceLogger(device);
    List<String> testSizes = getTestSizesForRun(testSize);
    SpoonTestRunListener spoonListener = new SpoonTestRunListener(result, debug, testIdentifierAdapter);
    XmlTestRunListener xmlListener = new XmlTestRunListener(junitReport);
    PullDeviceFilesListener pullDeviceListener = new PullDeviceFilesListener(new PullDeviceFilesListener.PullDeviceListener() {
      @Override
      public void pullDeviceFiles() {
        safePullDeviceFiles(device);
      }
    });
    Map<String, Integer> shardMap = new HashMap<String, Integer>();
    int totalShard = 0;
    for (String testSize : testSizes) {
      int count = getTestCount(testPackage, testRunner, device, testSize);
      int numShard = testCountInOneProcess > 0 ? count / testCountInOneProcess : 1;
      numShard = Math.max(numShard, 1);
      shardMap.put(testSize, numShard);
      totalShard += numShard;
    }

    spoonListener.setCountCycle(totalShard);
    xmlListener.setCountCycle(totalShard);
    for (String testSize : testSizes) {
      int numShard = shardMap.get(testSize);
      for (int i = 0; i < numShard; i++) {
        startRunner(testPackage, testRunner, result, device, i, numShard, testSize, Arrays.asList(spoonListener, xmlListener, pullDeviceListener));
      }
    }


    mapLogsToTests(deviceLogger, result);

    try {
      logDebug(debug, "About to grab screenshots and prepare output for [%s]", serial);
      pullDeviceFiles(device);

      File screenshotDir = new File(work, DEVICE_SCREENSHOT_DIR);
      File testFilesDir = new File(work, DEVICE_FILE_DIR);
      if (screenshotDir.exists()) {
        imageDir.mkdirs();

        handleImages(result, screenshotDir);
        FileUtils.deleteDirectory(screenshotDir);
      }
      if (testFilesDir.exists()) {
        fileDir.mkdirs();
        handleFiles(result, testFilesDir);
        FileUtils.deleteDirectory(testFilesDir);
      }
    } catch (Exception e) {
      result.addException(e);
    }
    logDebug(debug, "Done running for [%s]", serial);

    return result.build();
  }

  private List<String> getTestSizesForRun(String testSize) {
    List<String> result = new ArrayList<String>();
    if (testSize != null) {
      String[] sizes = testSize.split(",");
      for (String size : sizes) {
        if ("small".equals(size)) {
          result.add(SMALL);
        } else if ("medium".equals(size)) {
          result.add(MEDIUM);
        } else if ("large".equals(size)) {
          result.add(LARGE);
        } else if ("smoke".equals(size)) {
          result.add(SMOKE);
        }
      }
    }
    if (result.size() == 0) {
      result.add(null);
    }
    return result;
  }

  public void safePullDeviceFiles(IDevice device) {
    try {
      pullDeviceFiles(device);
    } catch (Exception ignored) {

    }
  }

  private int getTestCount(String testPackage, String testRunner, IDevice device, String testSize) {

    TestCountListener countListener = new TestCountListener();
    try {
      RemoteAndroidTestRunner runner = buildRunner(testPackage, testRunner, device, testSize);
      runner.setTestCollection(true);
      runner.run(countListener);
    } catch (TimeoutException e) {
      e.printStackTrace();
    } catch (AdbCommandRejectedException e) {
      e.printStackTrace();
    } catch (ShellCommandUnresponsiveException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return countListener.getCount();

  }

  private void startRunner(String testPackage, String testRunner, DeviceResult.Builder result, IDevice device,
                           int shardIndex, int numShards, String testSize, List<ITestRunListener> spoonListeners) {
    try {
      logDebug(debug, "About to actually run tests for [%s]", serial);
      RemoteAndroidTestRunner runner = buildRunner(testPackage, testRunner, device, testSize);
      runner.setMaxtimeToOutputResponse(adbTimeout);
      if (numShards > 0) {
        runner.addInstrumentationArg("numShards", String.valueOf(numShards));
        runner.addInstrumentationArg("shardIndex ", String.valueOf(shardIndex));
      }
      List<ITestRunListener> listeners = new ArrayList<ITestRunListener>();
      listeners.addAll(spoonListeners);
      if (testRunListeners != null) {
        listeners.addAll(testRunListeners);
      }
      runner.run(listeners);
    } catch (Exception e) {
      result.addException(e);
    }
  }

  private RemoteAndroidTestRunner buildRunner(String testPackage, String testRunner, IDevice device, String testSize) {
    RemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(testPackage, testRunner, device);
    if (instrumentationArgs != null && instrumentationArgs.size() > 0) {
      for (String pair : instrumentationArgs) {
        int firstEqualSignIndex = pair.indexOf("=");
        if (firstEqualSignIndex <= -1) {
          //No Equal Sign, can't process
          continue;
        }
        String key = pair.substring(0, firstEqualSignIndex);
        String value = pair.substring(firstEqualSignIndex + 1);
        if (isNullOrEmpty(key) || isNullOrEmpty(value)) {
          //invalid values, skipping
          continue;
        }
        runner.addInstrumentationArg(key, value);
      }
    }
    if (!isNullOrEmpty(className)) {
      if (!isNullOrEmpty(methodName)) {
        runner.setMethodName(className, methodName);
      } else {
        runner.setClassName(className);
      }
    }
    if (testSize != null) {
      runner.addInstrumentationArg("annotation", testSize);
    }
    return runner;
  }

  private void handleImages(DeviceResult.Builder result, File screenshotDir) throws IOException {
    logDebug(debug, "Moving screenshots to the image folder on [%s]", serial);
    // Move all children of the screenshot directory into the image folder.
    File[] classNameDirs = screenshotDir.listFiles();
    if (classNameDirs != null) {
      Multimap<DeviceTest, File> testScreenshots = ArrayListMultimap.create();
      for (File classNameDir : classNameDirs) {
        String className = classNameDir.getName();
        File destDir = new File(imageDir, className);
        FileUtils.copyDirectory(classNameDir, destDir);

        // Get a sorted list of all screenshots from the device run.
        List<File> screenshots = new ArrayList<File>(
            FileUtils.listFiles(destDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE));
        Collections.sort(screenshots);

        // Iterate over each screenshot and associate it with its corresponding method result.
        for (File screenshot : screenshots) {
          String methodName = screenshot.getParentFile().getName();

          DeviceTest testIdentifier = new DeviceTest(className, methodName);
          DeviceTestResult.Builder builder = result.getMethodResultBuilder(testIdentifier);
          if (builder != null) {
            builder.addScreenshot(screenshot);
            testScreenshots.put(testIdentifier, screenshot);
          } else {
            logError("Unable to find test for %s", testIdentifier);
          }
        }
      }

      logDebug(debug, "Generating animated gifs for [%s]", serial);
      // Don't generate animations if the switch is present
      if (!noAnimations) {
        // Make animated GIFs for all the tests which have screenshots.
        for (DeviceTest deviceTest : testScreenshots.keySet()) {
          List<File> screenshots = new ArrayList<File>(testScreenshots.get(deviceTest));
          if (screenshots.size() == 1) {
            continue; // Do not make an animated GIF if there is only one screenshot.
          }
          File animatedGif = FileUtils.getFile(imageDir, deviceTest.getClassName(),
              deviceTest.getMethodName() + ".gif");
          createAnimatedGif(screenshots, animatedGif);
          result.getMethodResultBuilder(deviceTest).setAnimatedGif(animatedGif);
        }
      }
    }
  }

  private void handleFiles(DeviceResult.Builder result, File testFileDir) throws IOException {
    File[] classNameDirs = testFileDir.listFiles();
    if (classNameDirs != null) {
      logInfo("Found class name dirs: " + classNameDirs);
      Multimap<DeviceTest, File> testFiles = ArrayListMultimap.create();
      for (File classNameDir : classNameDirs) {
        String className = classNameDir.getName();
        File destDir = new File(fileDir, className);
        FileUtils.copyDirectory(classNameDir, destDir);
        logInfo("Copied " + classNameDir + " to " + destDir);

        // Get a sorted list of all files from the device run.
        List<File> files = new ArrayList<File>(
            FileUtils.listFiles(destDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE));
        Collections.sort(files);

        // Iterate over each file and associate it with its
        // corresponding method result.
        for (File file : files) {
          String methodName = file.getParentFile().getName();
          DeviceTest testIdentifier = new DeviceTest(className, methodName);
          final DeviceTestResult.Builder resultBuilder
              = result.getMethodResultBuilder(testIdentifier);
          if (resultBuilder != null) {
            resultBuilder.addFile(file);
            logInfo("Added file as result: " + file + " for " + testIdentifier);
          } else {
            logError("Unable to find test for %s", testIdentifier);
          }
        }
      }
    }
  }

  /**
   * Download all files from a single device to the local machine.
   */
  private void pullDeviceFiles(IDevice device) throws Exception {
    for (String dir : DEVICE_DIRS) {
      pullDirectory(device, dir);
    }
  }

  private void pullDirectory(final IDevice device, final String name) throws Exception {
    // Output path on private internal storage, for KitKat and below.
    FileEntry internalDir = getScreenshotDirOnInternalStorage(name);
    logDebug(debug, "Internal path is " + internalDir.getFullPath());

    // Output path on public external storage, for Lollipop and above.
    FileEntry externalDir = getScreenshotDirOnExternalStorage(device, name);
    logDebug(debug, "External path is " + externalDir.getFullPath());

    // Sync test output files to the local filesystem.
    logDebug(debug, "Pulling files from external dir on [%s]", serial);
    String localDirName = work.getAbsolutePath();
    adbPull(device, externalDir, localDirName);
    logDebug(debug, "Pulling files from internal dir on [%s]", serial);
    adbPull(device, internalDir, localDirName);
    logDebug(debug, "Done pulling %s from on [%s]", name, serial);
  }

  private void adbPull(IDevice device, FileEntry remoteDirName, String localDirName) {
    try {
      device.getSyncService()
          .pull(new FileEntry[]{remoteDirName}, localDirName,
              SyncService.getNullProgressMonitor());
    } catch (Exception e) {
      logDebug(debug, e.getMessage(), e);
    }
  }

  private FileEntry getScreenshotDirOnInternalStorage(final String dir) {
    String appPackage = instrumentationInfo.getApplicationPackage();
    String internalPath = "/data/data/" + appPackage + "/" + dir;
    return obtainDirectoryFileEntry(internalPath);
  }

  private static FileEntry getScreenshotDirOnExternalStorage(IDevice device, final String dir)
      throws Exception {
    String externalPath = getExternalStoragePath(device) + "/" + dir;
    return obtainDirectoryFileEntry(externalPath);
  }

  private static String getExternalStoragePath(IDevice device) throws Exception {
    CollectingOutputReceiver pathNameOutputReceiver = new CollectingOutputReceiver();
    device.executeShellCommand("echo $EXTERNAL_STORAGE", pathNameOutputReceiver);
    return pathNameOutputReceiver.getOutput().trim();
  }

  /**
   * Grab all the parsed logs and map them to individual tests.
   */
  private static void mapLogsToTests(SpoonDeviceLogger deviceLogger, DeviceResult.Builder result) {
    Map<DeviceTest, List<LogCatMessage>> logs = deviceLogger.getParsedLogs();
    for (Map.Entry<DeviceTest, List<LogCatMessage>> entry : logs.entrySet()) {
      DeviceTestResult.Builder builder = result.getMethodResultBuilder(entry.getKey());
      if (builder != null) {
        builder.setLog(entry.getValue());
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////
  ////  Secondary Per-Device Process  /////////////////////////////////////////
  /////////////////////////////////////////////////////////////////////////////

  /**
   * De-serialize from disk, run the tests, and serialize the result back to disk.
   */
  public static void main(String... args) {
    if (args.length != 1) {
      throw new IllegalArgumentException("Must be started with a device directory.");
    }

    try {
      String outputDirName = args[0];
      File outputDir = new File(outputDirName);
      File executionFile = new File(outputDir, FILE_EXECUTION);
      if (!executionFile.exists()) {
        throw new IllegalArgumentException("Device directory and/or execution file doesn't exist.");
      }

      FileReader reader = new FileReader(executionFile);
      SpoonDeviceRunner target = GSON.fromJson(reader, SpoonDeviceRunner.class);
      reader.close();

      AndroidDebugBridge adb = SpoonUtils.initAdb(target.sdk, target.adbTimeout);
      DeviceResult result = target.run(adb);
      AndroidDebugBridge.terminate();

      // Write device result file.
      FileWriter writer = new FileWriter(new File(outputDir, FILE_RESULT));
      GSON.toJson(result, writer);
      writer.close();
    } catch (Throwable ex) {
      logInfo("ERROR: Unable to execute test for target.  Exception message: %s", ex.getMessage());
      ex.printStackTrace(System.out);
      System.exit(1);
    }
  }
}
