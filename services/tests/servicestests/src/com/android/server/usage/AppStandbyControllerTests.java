/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.usage;

import static android.app.usage.UsageEvents.Event.NOTIFICATION_SEEN;
import static android.app.usage.UsageEvents.Event.USER_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_FORCED;
import static android.app.usage.UsageStatsManager.REASON_PREDICTED;
import static android.app.usage.UsageStatsManager.REASON_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_USAGE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;

import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit test for AppStandbyController.
 */
@RunWith(AndroidJUnit4.class)
@Presubmit
@SmallTest
public class AppStandbyControllerTests {

    private static final String PACKAGE_1 = "com.example.foo";
    private static final int UID_1 = 10000;
    private static final int USER_ID = 0;

    private static final long MINUTE_MS = 60 * 1000;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long DAY_MS = 24 * HOUR_MS;

    private static final long WORKING_SET_THRESHOLD = 12 * HOUR_MS;
    private static final long FREQUENT_THRESHOLD = 24 * HOUR_MS;
    private static final long RARE_THRESHOLD = 48 * HOUR_MS;

    private MyInjector mInjector;
    private AppStandbyController mController;

    static class MyContextWrapper extends ContextWrapper {
        PackageManager mockPm = mock(PackageManager.class);

        public MyContextWrapper(Context base) {
            super(base);
        }

        public PackageManager getPackageManager() {
            return mockPm;
        }
    }

    static class MyInjector extends AppStandbyController.Injector {
        long mElapsedRealtime;
        boolean mIsCharging;
        List<String> mPowerSaveWhitelistExceptIdle = new ArrayList<>();
        boolean mDisplayOn;
        DisplayManager.DisplayListener mDisplayListener;
        String mBoundWidgetPackage;

        MyInjector(Context context, Looper looper) {
            super(context, looper);
        }

        @Override
        void onBootPhase(int phase) {
        }

        @Override
        int getBootPhase() {
            return SystemService.PHASE_BOOT_COMPLETED;
        }

        @Override
        long elapsedRealtime() {
            return mElapsedRealtime;
        }

        @Override
        long currentTimeMillis() {
            return mElapsedRealtime;
        }

        @Override
        boolean isAppIdleEnabled() {
            return true;
        }

        @Override
        boolean isCharging() {
            return mIsCharging;
        }

        @Override
        boolean isPowerSaveWhitelistExceptIdleApp(String packageName) throws RemoteException {
            return mPowerSaveWhitelistExceptIdle.contains(packageName);
        }

        @Override
        File getDataSystemDirectory() {
            return new File(getContext().getFilesDir(), Long.toString(Math.randomLongInternal()));
        }

        @Override
        void noteEvent(int event, String packageName, int uid) throws RemoteException {
        }

        @Override
        boolean isPackageEphemeral(int userId, String packageName) {
            // TODO: update when testing ephemeral apps scenario
            return false;
        }

        @Override
        int[] getRunningUserIds() {
            return new int[] {USER_ID};
        }

        @Override
        boolean isDefaultDisplayOn() {
            return mDisplayOn;
        }

        @Override
        void registerDisplayListener(DisplayManager.DisplayListener listener, Handler handler) {
            mDisplayListener = listener;
        }

        @Override
        String getActiveNetworkScorer() {
            return null;
        }

        @Override
        public boolean isBoundWidgetPackage(AppWidgetManager appWidgetManager, String packageName,
                int userId) {
            return packageName != null && packageName.equals(mBoundWidgetPackage);
        }

        @Override
        String getAppIdleSettings() {
            return "screen_thresholds=0/0/0/" + HOUR_MS + ",elapsed_thresholds=0/"
                    + WORKING_SET_THRESHOLD + "/"
                    + FREQUENT_THRESHOLD + "/"
                    + RARE_THRESHOLD;
        }

        // Internal methods

        void setDisplayOn(boolean on) {
            mDisplayOn = on;
            if (mDisplayListener != null) {
                mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
            }
        }
    }

    private void setupPm(PackageManager mockPm) throws PackageManager.NameNotFoundException {
        List<PackageInfo> packages = new ArrayList<>();
        PackageInfo pi = new PackageInfo();
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = UID_1;
        pi.packageName = PACKAGE_1;
        packages.add(pi);

        doReturn(packages).when(mockPm).getInstalledPackagesAsUser(anyInt(), anyInt());
        try {
            doReturn(UID_1).when(mockPm).getPackageUidAsUser(anyString(), anyInt(), anyInt());
            doReturn(pi.applicationInfo).when(mockPm).getApplicationInfo(anyString(), anyInt());
        } catch (PackageManager.NameNotFoundException nnfe) {}
    }

    private void setChargingState(AppStandbyController controller, boolean charging) {
        mInjector.mIsCharging = charging;
        if (controller != null) {
            controller.setChargingState(charging);
        }
    }

    private AppStandbyController setupController() throws Exception {
        mInjector.mElapsedRealtime = 0;
        AppStandbyController controller = new AppStandbyController(mInjector);
        controller.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        controller.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mInjector.setDisplayOn(false);
        mInjector.setDisplayOn(true);
        setChargingState(controller, false);
        setupPm(mInjector.getContext().getPackageManager());
        controller.checkIdleStates(USER_ID);

        return controller;
    }

    @Before
    public void setUp() throws Exception {
        MyContextWrapper myContext = new MyContextWrapper(InstrumentationRegistry.getContext());
        mInjector = new MyInjector(myContext, Looper.getMainLooper());
        mController = setupController();
    }

    @Test
    public void testCharging() throws Exception {
        setChargingState(mController, true);
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 1;
        assertFalse(mController.isAppIdleFilteredOrParoled(PACKAGE_1, USER_ID,
                mInjector.mElapsedRealtime, false));

        setChargingState(mController, false);
        mInjector.mElapsedRealtime = 2 * RARE_THRESHOLD + 2;
        mController.checkIdleStates(USER_ID);
        assertTrue(mController.isAppIdleFilteredOrParoled(PACKAGE_1, USER_ID,
                mInjector.mElapsedRealtime, false));
        setChargingState(mController, true);
        assertFalse(mController.isAppIdleFilteredOrParoled(PACKAGE_1,USER_ID,
                mInjector.mElapsedRealtime, false));
    }

    private void assertTimeout(AppStandbyController controller, long elapsedTime, int bucket) {
        mInjector.mElapsedRealtime = elapsedTime;
        controller.checkIdleStates(USER_ID);
        assertEquals(bucket,
                controller.getAppStandbyBucket(PACKAGE_1, USER_ID, mInjector.mElapsedRealtime,
                        false));
    }

    private void reportEvent(AppStandbyController controller, int eventType,
            long elapsedTime) {
        // Back to ACTIVE on event
        UsageEvents.Event ev = new UsageEvents.Event();
        ev.mPackage = PACKAGE_1;
        ev.mEventType = eventType;
        controller.reportEvent(ev, elapsedTime, USER_ID);
    }

    private int getStandbyBucket(AppStandbyController controller) {
        return controller.getAppStandbyBucket(PACKAGE_1, USER_ID, mInjector.mElapsedRealtime,
                true);
    }

    private void assertBucket(int bucket) {
        assertEquals(bucket, getStandbyBucket(mController));
    }

    @Test
    public void testBuckets() throws Exception {
        assertTimeout(mController, 0, STANDBY_BUCKET_NEVER);

        reportEvent(mController, USER_INTERACTION, 0);

        // ACTIVE bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE);

        // WORKING_SET bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET);

        // WORKING_SET bucket
        assertTimeout(mController, FREQUENT_THRESHOLD - 1, STANDBY_BUCKET_WORKING_SET);

        // FREQUENT bucket
        assertTimeout(mController, FREQUENT_THRESHOLD + 1, STANDBY_BUCKET_FREQUENT);

        // RARE bucket
        assertTimeout(mController, RARE_THRESHOLD + 1, STANDBY_BUCKET_RARE);

        reportEvent(mController, USER_INTERACTION, RARE_THRESHOLD + 1);

        assertTimeout(mController, RARE_THRESHOLD + 1, STANDBY_BUCKET_ACTIVE);

        // RARE bucket
        assertTimeout(mController, RARE_THRESHOLD * 2 + 2, STANDBY_BUCKET_RARE);
    }

    @Test
    public void testScreenTimeAndBuckets() throws Exception {
        mInjector.setDisplayOn(false);

        assertTimeout(mController, 0, STANDBY_BUCKET_NEVER);

        reportEvent(mController, USER_INTERACTION, 0);

        // ACTIVE bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD - 1, STANDBY_BUCKET_ACTIVE);

        // WORKING_SET bucket
        assertTimeout(mController, WORKING_SET_THRESHOLD + 1, STANDBY_BUCKET_WORKING_SET);

        // RARE bucket, should fail because the screen wasn't ON.
        mInjector.mElapsedRealtime = RARE_THRESHOLD + 1;
        mController.checkIdleStates(USER_ID);
        assertNotEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController));

        mInjector.setDisplayOn(true);
        assertTimeout(mController, RARE_THRESHOLD * 2 + 2, STANDBY_BUCKET_RARE);
    }

    @Test
    public void testForcedIdle() throws Exception {
        setChargingState(mController, false);

        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController));
        assertTrue(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));

        mController.forceIdleState(PACKAGE_1, USER_ID, false);
        assertEquals(STANDBY_BUCKET_ACTIVE, mController.getAppStandbyBucket(PACKAGE_1, USER_ID, 0,
                true));
        assertFalse(mController.isAppIdleFiltered(PACKAGE_1, UID_1, USER_ID, 0));
    }

    @Test
    public void testNotificationEvent() throws Exception {
        setChargingState(mController, false);

        reportEvent(mController, USER_INTERACTION, 0);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));
        mInjector.mElapsedRealtime = 1;
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));

        mController.forceIdleState(PACKAGE_1, USER_ID, true);
        reportEvent(mController, NOTIFICATION_SEEN, mInjector.mElapsedRealtime);
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController));
    }

    @Test
    public void testPredictionTimedout() throws Exception {
        setChargingState(mController, false);
        // Set it to timeout or usage, so that prediction can override it
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_RARE,
                REASON_TIMEOUT, 1 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController));

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_PREDICTED + ":CTS", 1 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));

        // Fast forward 12 hours
        mInjector.mElapsedRealtime += WORKING_SET_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        // Should still be in predicted bucket, since prediction timeout is 1 day since prediction
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));
        // Fast forward two more hours
        mInjector.mElapsedRealtime += 2 * HOUR_MS;
        mController.checkIdleStates(USER_ID);
        // Should have now applied prediction timeout
        assertEquals(STANDBY_BUCKET_WORKING_SET, getStandbyBucket(mController));

        // Fast forward RARE bucket
        mInjector.mElapsedRealtime += RARE_THRESHOLD;
        mController.checkIdleStates(USER_ID);
        // Should continue to apply prediction timeout
        assertEquals(STANDBY_BUCKET_RARE, getStandbyBucket(mController));
    }

    @Test
    public void testOverrides() throws Exception {
        setChargingState(mController, false);
        // Can force to NEVER
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_FORCED, 1 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_NEVER, getStandbyBucket(mController));

        // Prediction can't override FORCED reason
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_FORCED, 1 * HOUR_MS);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_PREDICTED, 1 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_FREQUENT, getStandbyBucket(mController));

        // Prediction can't override NEVER
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_DEFAULT, 2 * HOUR_MS);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_PREDICTED, 2 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_NEVER, getStandbyBucket(mController));

        // Prediction can't set to NEVER
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_USAGE, 2 * HOUR_MS);
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_NEVER,
                REASON_PREDICTED, 2 * HOUR_MS);
        assertEquals(STANDBY_BUCKET_ACTIVE, getStandbyBucket(mController));
    }

    @Test
    public void testTimeout() throws Exception {
        setChargingState(mController, false);

        reportEvent(mController, USER_INTERACTION, 0);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        mInjector.mElapsedRealtime = 2000;
        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_PREDICTED, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_ACTIVE);

        // bucketing works after timeout
        mInjector.mElapsedRealtime = FREQUENT_THRESHOLD - 100;
        mController.checkIdleStates(USER_ID);
        assertBucket(STANDBY_BUCKET_WORKING_SET);

        mController.setAppStandbyBucket(PACKAGE_1, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_PREDICTED, mInjector.mElapsedRealtime);
        assertBucket(STANDBY_BUCKET_FREQUENT);

    }
}
