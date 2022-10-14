/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.virt.fs;

import static com.android.microdroid.test.host.LogArchiver.archiveLogThenDelete;
import static com.android.tradefed.device.TestDevice.MicrodroidBuilder;
import static com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.compatibility.common.util.PollingCheck;
import com.android.microdroid.test.host.CommandRunner;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;

import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Custom TestRule for AuthFs tests. */
public class AuthFsTestRule extends TestLogData {
    /** FUSE's magic from statfs(2) */
    static final String FUSE_SUPER_MAGIC_HEX = "65735546";

    /** VM config entry path in the test APK */
    private static final String VM_CONFIG_PATH_IN_APK = "assets/vm_config.json";

    /** Test directory on Android where data are located */
    static final String TEST_DIR = "/data/local/tmp/authfs";

    /** File name of the test APK */
    private static final String TEST_APK_NAME = "MicrodroidTestApp.apk";

    /** Output directory where the test can generate output on Android */
    private static final String TEST_OUTPUT_DIR = "/data/local/tmp/authfs/output_dir";

    /** Mount point of authfs on Microdroid during the test */
    static final String MOUNT_DIR = "/data/local/tmp/mnt";

    /** VM's log file */
    private static final String LOG_PATH = TEST_OUTPUT_DIR + "/log.txt";

    /** Path to open_then_run on Android */
    private static final String OPEN_THEN_RUN_BIN = "/data/local/tmp/open_then_run";

    /** Path to fd_server on Android */
    private static final String FD_SERVER_BIN = "/apex/com.android.virt/bin/fd_server";

    /** Path to authfs on Microdroid */
    private static final String AUTHFS_BIN = "/system/bin/authfs";

    /** Plenty of time for authfs to get ready */
    private static final int AUTHFS_INIT_TIMEOUT_MS = 3000;

    private static final int VMADDR_CID_HOST = 2;

    private static TestInformation sTestInfo;
    private static ITestDevice sMicrodroidDevice;
    private static CommandRunner sAndroid;
    private static CommandRunner sMicrodroid;

    private final ExecutorService mThreadPool = Executors.newCachedThreadPool();

    static void setUpClass(TestInformation testInfo) throws Exception {
        assertNotNull(testInfo.getDevice());
        if (!(testInfo.getDevice() instanceof TestDevice)) {
            CLog.w("Unexpected type of ITestDevice. Skipping.");
            return;
        }
        sTestInfo = testInfo;
        TestDevice androidDevice = getDevice();
        sAndroid = new CommandRunner(androidDevice);

        // NB: We can't use assumeTrue because the assumption exception is NOT handled by the test
        // infra when it is thrown from a class method (see b/37502066). We need to skip both here
        // and in setUp.
        if (!androidDevice.supportsMicrodroid()) {
            CLog.i("Microdroid not supported. Skipping.");
            return;
        }

        // For each test case, boot and adb connect to a new Microdroid
        CLog.i("Starting the shared VM");
        sMicrodroidDevice =
                MicrodroidBuilder.fromFile(
                                findTestFile(testInfo.getBuildInfo(), TEST_APK_NAME),
                                VM_CONFIG_PATH_IN_APK)
                        .debugLevel("full")
                        .build((TestDevice) androidDevice);

        // From this point on, we need to tear down the Microdroid instance
        sMicrodroid = new CommandRunner(sMicrodroidDevice);

        sMicrodroid.runForResult("mkdir -p " + MOUNT_DIR);

        // Root because authfs (started from shell in this test) currently require root to open
        // /dev/fuse and mount the FUSE.
        assertThat(sMicrodroidDevice.enableAdbRoot()).isTrue();
    }

    static void tearDownClass(TestInformation testInfo) throws DeviceNotAvailableException {
        assertNotNull(sAndroid);

        if (sMicrodroid != null) {
            CLog.i("Shutting down shared VM");
            ((TestDevice) testInfo.getDevice()).shutdownMicrodroid(sMicrodroid.getDevice());
            sMicrodroid = null;
        }

        sAndroid = null;
    }

    /** This method is supposed to be called after {@link #setUpTest()}. */
    static CommandRunner getAndroid() {
        assertThat(sAndroid).isNotNull();
        return sAndroid;
    }

    /** This method is supposed to be called after {@link #setUpTest()}. */
    static CommandRunner getMicrodroid() {
        assertThat(sMicrodroid).isNotNull();
        return sMicrodroid;
    }

    static ITestDevice getMicrodroidDevice() {
        assertThat(sMicrodroidDevice).isNotNull();
        return sMicrodroidDevice;
    }

    @Override
    public Statement apply(final Statement base, Description description) {
        return super.apply(
                new Statement() {
                    @Override
                    public void evaluate() throws Throwable {
                        setUpTest();
                        base.evaluate();
                        tearDownTest(description.getMethodName());
                    }
                },
                description);
    }

    void runFdServerOnAndroid(String helperFlags, String fdServerFlags)
            throws DeviceNotAvailableException {
        String cmd =
                "cd "
                        + TEST_DIR
                        + " && "
                        + OPEN_THEN_RUN_BIN
                        + " "
                        + helperFlags
                        + " -- "
                        + FD_SERVER_BIN
                        + " "
                        + fdServerFlags;
        Future<?> unusedFuture = mThreadPool.submit(() -> runForResult(sAndroid, cmd, "fd_server"));
    }

    void runAuthFsOnMicrodroid(String flags) {
        String cmd = AUTHFS_BIN + " " + MOUNT_DIR + " " + flags + " --cid " + VMADDR_CID_HOST;

        AtomicBoolean starting = new AtomicBoolean(true);
        Future<?> unusedFuture =
                mThreadPool.submit(
                        () -> {
                            // authfs may fail to start if fd_server is not yet listening on the
                            // vsock
                            // ("Error: Invalid raw AIBinder"). Just restart if that happens.
                            while (starting.get()) {
                                runForResult(sMicrodroid, cmd, "authfs");
                            }
                        });
        try {
            PollingCheck.waitFor(
                    AUTHFS_INIT_TIMEOUT_MS, () -> isMicrodroidDirectoryOnFuse(MOUNT_DIR));
        } catch (Exception e) {
            // Convert the broad Exception into an unchecked exception to avoid polluting all other
            // methods. waitFor throws Exception because the callback, Callable#call(), has a
            // signature to throw an Exception.
            throw new RuntimeException(e);
        } finally {
            starting.set(false);
        }
    }

    static File findTestFile(IBuildInfo buildInfo, String fileName) {
        try {
            return (new CompatibilityBuildHelper(buildInfo)).getTestFile(fileName);
        } catch (FileNotFoundException e) {
            fail("Missing test file: " + fileName);
            return null;
        }
    }

    private static TestDevice getDevice() {
        return (TestDevice) sTestInfo.getDevice();
    }

    private void runForResult(CommandRunner cmdRunner, String cmd, String serviceName) {
        try {
            CLog.i("Starting " + serviceName);
            CommandResult result = cmdRunner.runForResult(cmd);
            CLog.w(serviceName + " has stopped: " + result);
        } catch (DeviceNotAvailableException e) {
            CLog.e("Error running " + serviceName, e);
            throw new RuntimeException(e);
        }
    }

    private boolean isMicrodroidDirectoryOnFuse(String path) throws DeviceNotAvailableException {
        String fs_type = sMicrodroid.tryRun("stat -f -c '%t' " + path);
        return FUSE_SUPER_MAGIC_HEX.equals(fs_type);
    }

    private void setUpTest() throws Exception {
        assumeTrue(getDevice().supportsMicrodroid());
        sAndroid.run("mkdir -p " + TEST_OUTPUT_DIR);
    }

    private void tearDownTest(String testName) throws Exception {
        if (sMicrodroid != null) {
            sMicrodroid.tryRun("killall authfs");
            sMicrodroid.tryRun("umount " + MOUNT_DIR);
        }

        assertNotNull(sAndroid);
        sAndroid.tryRun("killall fd_server");

        // Even though we only run one VM for the whole class, and could have collect the VM log
        // after all tests are done, TestLogData doesn't seem to work at class level. Hence,
        // collect recent logs manually for each test method.
        String vmRecentLog = TEST_OUTPUT_DIR + "/vm_recent.log";
        sAndroid.tryRun("tail -n 50 " + LOG_PATH + " > " + vmRecentLog);
        archiveLogThenDelete(this, getDevice(), vmRecentLog, "vm_recent.log-" + testName);

        sAndroid.run("rm -rf " + TEST_OUTPUT_DIR);
    }
}
