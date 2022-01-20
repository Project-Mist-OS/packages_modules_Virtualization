/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.compos.test;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.RootPermissionTest;
import android.virt.test.CommandRunner;
import android.virt.test.VirtualizationTestCaseBase;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.util.CommandResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RootPermissionTest
@RunWith(DeviceJUnit4ClassRunner.class)
public final class ComposTestCase extends VirtualizationTestCaseBase {

    // Binaries used in test. (These paths are valid both in host and Microdroid.)
    private static final String ODREFRESH_BIN = "/apex/com.android.art/bin/odrefresh";
    private static final String COMPOSD_CMD_BIN = "/apex/com.android.compos/bin/composd_cmd";

    /** Output directory of odrefresh */
    private static final String TEST_ARTIFACTS_DIR = "test-artifacts";

    private static final String ODREFRESH_OUTPUT_DIR =
            "/data/misc/apexdata/com.android.art/" + TEST_ARTIFACTS_DIR;

    /** Timeout of odrefresh to finish */
    private static final int ODREFRESH_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes

    // ExitCode expanded from art/odrefresh/include/odrefresh/odrefresh.h.
    private static final int OKAY = 0;
    private static final int COMPILATION_SUCCESS = 80;

    // Files that define the "test" instance of CompOS
    private static final String COMPOS_TEST_ROOT = "/data/misc/apexdata/com.android.compos/test/";

    private static final String SYSTEM_SERVER_COMPILER_FILTER_PROP_NAME =
            "dalvik.vm.systemservercompilerfilter";
    private String mBackupSystemServerCompilerFilter;

    @Before
    public void setUp() throws Exception {
        testIfDeviceIsCapable(getDevice());

        String value = getDevice().getProperty(SYSTEM_SERVER_COMPILER_FILTER_PROP_NAME);
        if (value == null) {
            mBackupSystemServerCompilerFilter = "";
        } else {
            mBackupSystemServerCompilerFilter = value;
        }
    }

    @After
    public void tearDown() throws Exception {
        killVmAndReconnectAdb();

        CommandRunner android = new CommandRunner(getDevice());

        // Clear up any CompOS instance files we created
        android.tryRun("rm", "-rf", COMPOS_TEST_ROOT);

        // And any artifacts generated by odrefresh
        android.tryRun("rm", "-rf", ODREFRESH_OUTPUT_DIR);

        if (mBackupSystemServerCompilerFilter != null) {
            CLog.d("Restore dalvik.vm.systemservercompilerfilter to "
                    + mBackupSystemServerCompilerFilter);
            getDevice().setProperty(SYSTEM_SERVER_COMPILER_FILTER_PROP_NAME,
                    mBackupSystemServerCompilerFilter);
        }
    }

    @Test
    public void testOdrefreshSpeed() throws Exception {
        getDevice().setProperty(SYSTEM_SERVER_COMPILER_FILTER_PROP_NAME, "speed");
        testOdrefresh();
    }

    @Test
    public void testOdrefreshSpeedProfile() throws Exception {
        getDevice().setProperty(SYSTEM_SERVER_COMPILER_FILTER_PROP_NAME, "speed-profile");
        testOdrefresh();
    }

    private void testOdrefresh() throws Exception {
        CommandRunner android = new CommandRunner(getDevice());

        // Prepare the groundtruth. The compilation on Android should finish successfully.
        {
            long start = System.currentTimeMillis();
            CommandResult result = runOdrefresh(android, "--force-compile");
            long elapsed = System.currentTimeMillis() - start;
            assertThat(result.getExitCode()).isEqualTo(COMPILATION_SUCCESS);
            CLog.i("Local compilation took " + elapsed + "ms");
        }

        // Save the expected checksum for the output directory.
        String expectedChecksumSnapshot = checksumDirectoryContentPartial(android,
                ODREFRESH_OUTPUT_DIR);

        // --check may delete the output.
        CommandResult result = runOdrefresh(android, "--check");
        assertThat(result.getExitCode()).isEqualTo(OKAY);

        // Make sure we generate a fresh instance.
        android.tryRun("rm", "-rf", COMPOS_TEST_ROOT);
        // TODO: remove once composd starts to clean up the directory.
        android.tryRun("rm", "-rf", ODREFRESH_OUTPUT_DIR);

        // Expect the compilation in Compilation OS to finish successfully.
        {
            long start = System.currentTimeMillis();
            result =
                    android.runForResultWithTimeout(
                            ODREFRESH_TIMEOUT_MS, COMPOSD_CMD_BIN, "test-compile");
            long elapsed = System.currentTimeMillis() - start;
            assertThat(result.getExitCode()).isEqualTo(0);
            CLog.i("Comp OS compilation took " + elapsed + "ms");
        }
        killVmAndReconnectAdb();

        // Save the actual checksum for the output directory.
        String actualChecksumSnapshot = checksumDirectoryContentPartial(android,
                ODREFRESH_OUTPUT_DIR);

        // Expect the output of Comp OS to be the same as compiled on Android.
        assertThat(actualChecksumSnapshot).isEqualTo(expectedChecksumSnapshot);

        // Expect extra files generated by CompOS exist.
        android.assumeSuccess("test -f " + ODREFRESH_OUTPUT_DIR + "/compos.info");
        android.assumeSuccess("test -f " + ODREFRESH_OUTPUT_DIR + "/compos.info.signature");
    }

    private CommandResult runOdrefresh(CommandRunner android, String command) throws Exception {
        return android.runForResultWithTimeout(
                ODREFRESH_TIMEOUT_MS,
                ODREFRESH_BIN,
                "--dalvik-cache=" + TEST_ARTIFACTS_DIR,
                command);
    }

    private void killVmAndReconnectAdb() throws Exception {
        CommandRunner android = new CommandRunner(getDevice());

        // When a VM exits, we tend to see adb disconnecting. So we attempt to reconnect
        // when we kill it to avoid problems. Of course VirtualizationService may exit anyway
        // (it's an on-demand service and all its clients have gone), taking the VM with it,
        // which makes this a bit unpredictable.
        reconnectHostAdb(getDevice());
        android.tryRun("killall", "crosvm");
        reconnectHostAdb(getDevice());
        android.tryRun("stop", "virtualizationservice");
        reconnectHostAdb(getDevice());

        // Delete stale data
        android.tryRun("rm", "-rf", "/data/misc/virtualizationservice/*");
    }

    private String checksumDirectoryContentPartial(CommandRunner runner, String path)
            throws Exception {
        // Sort by filename (second column) to make comparison easier. Filter out compos.info and
        // compos.info.signature since it's only generated by CompOS.
        // TODO(b/211458160): Remove cache-info.xml once we can plumb timestamp and isFactory of
        // APEXes to the VM.
        return runner.run("cd " + path + "; find -type f -exec sha256sum {} \\;"
                + "| grep -v cache-info.xml | grep -v compos.info"
                + "| sort -k2");
    }
}
