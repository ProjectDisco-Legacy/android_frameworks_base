/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server;

import com.android.common.FastXmlSerializer;
import com.android.internal.widget.LockPatternUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.Activity;
import android.app.DeviceAdmin;
import android.app.DeviceAdminInfo;
import android.app.DevicePolicyManager;
import android.app.IDevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.RecoverySystem;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Implementation of the device policy APIs.
 */
public class DevicePolicyManagerService extends IDevicePolicyManager.Stub {
    private static final String TAG = "DevicePolicyManagerService";
    
    private final Context mContext;

    IPowerManager mIPowerManager;
    
    int mActivePasswordMode = DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED;
    int mActivePasswordLength = 0;
    int mFailedPasswordAttempts = 0;
    
    ActiveAdmin mActiveAdmin;
    
    static class ActiveAdmin {
        final DeviceAdminInfo info;
        
        int passwordMode = DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED;
        int minimumPasswordLength = 0;
        long maximumTimeToUnlock = 0;
        int maximumFailedPasswordsForWipe = 0;
        
        ActiveAdmin(DeviceAdminInfo _info) {
            info = _info;
        }
        
        int getUid() { return info.getActivityInfo().applicationInfo.uid; }
        
        void writeToXml(XmlSerializer out)
                throws IllegalArgumentException, IllegalStateException, IOException {
            if (passwordMode != DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED) {
                out.startTag(null, "password-mode");
                out.attribute(null, "value", Integer.toString(passwordMode));
                out.endTag(null, "password-mode");
                if (minimumPasswordLength > 0) {
                    out.startTag(null, "min-password-length");
                    out.attribute(null, "value", Integer.toString(minimumPasswordLength));
                    out.endTag(null, "mn-password-length");
                }
            }
            if (maximumTimeToUnlock != DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED) {
                out.startTag(null, "max-time-to-unlock");
                out.attribute(null, "value", Long.toString(maximumTimeToUnlock));
                out.endTag(null, "max-time-to-unlock");
            }
            if (maximumFailedPasswordsForWipe != 0) {
                out.startTag(null, "max-failed-password-wipe");
                out.attribute(null, "value", Integer.toString(maximumFailedPasswordsForWipe));
                out.endTag(null, "max-failed-password-wipe");
            }
        }
        
        void readFromXml(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            int outerDepth = parser.getDepth();
            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                String tag = parser.getName();
                if ("password-mode".equals(tag)) {
                    passwordMode = Integer.parseInt(
                            parser.getAttributeValue(null, "value"));
                } else if ("min-password-length".equals(tag)) {
                    minimumPasswordLength = Integer.parseInt(
                            parser.getAttributeValue(null, "value"));
                } else if ("max-time-to-unlock".equals(tag)) {
                    maximumTimeToUnlock = Long.parseLong(
                            parser.getAttributeValue(null, "value"));
                } else if ("max-failed-password-wipe".equals(tag)) {
                    maximumFailedPasswordsForWipe = Integer.parseInt(
                            parser.getAttributeValue(null, "value"));
                }
            }
        }
    }
    
    /**
     * Instantiates the service.
     */
    public DevicePolicyManagerService(Context context) {
        mContext = context;
    }

    private IPowerManager getIPowerManager() {
        if (mIPowerManager == null) {
            IBinder b = ServiceManager.getService(Context.POWER_SERVICE);
            mIPowerManager = IPowerManager.Stub.asInterface(b);
        }
        return mIPowerManager;
    }
    
    ActiveAdmin getActiveAdminUncheckedLocked(ComponentName who) {
        ActiveAdmin admin = mActiveAdmin;
        if (admin != null
                && who.getPackageName().equals(admin.info.getActivityInfo().packageName)
                && who.getClassName().equals(admin.info.getActivityInfo().name)) {
            return admin;
        }
        return null;
    }
    
    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who)
            throws SecurityException {
        ActiveAdmin admin = mActiveAdmin;
        if (admin != null && admin.getUid() == Binder.getCallingUid()) {
            if (who != null) {
                if (!who.getPackageName().equals(admin.info.getActivityInfo().packageName)
                        || !who.getClassName().equals(admin.info.getActivityInfo().name)) {
                    throw new SecurityException("Current admin is not " + who);
                }
            }
            return mActiveAdmin;
        }
        throw new SecurityException("Current admin is not owned by uid " + Binder.getCallingUid());
    }
    
    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who, int reqPolicy)
            throws SecurityException {
        ActiveAdmin admin = getActiveAdminForCallerLocked(who);
        if (!admin.info.usesPolicy(reqPolicy)) {
            throw new SecurityException("Admin " + admin.info.getComponent()
                    + " did not specify uses-policy for: "
                    + admin.info.getTagForPolicy(reqPolicy));
        }
        return admin;
    }
    
    void sendAdminCommandLocked(ActiveAdmin admin, String action) {
        Intent intent = new Intent(action);
        intent.setComponent(admin.info.getComponent());
        mContext.sendBroadcast(intent);
    }
    
    void sendAdminCommandLocked(String action, int reqPolicy) {
        if (mActiveAdmin != null) {
            if (mActiveAdmin.info.usesPolicy(reqPolicy)) {
                return;
            }
            sendAdminCommandLocked(mActiveAdmin, action);
        }
    }
    
    ComponentName getActiveAdminLocked() {
        if (mActiveAdmin != null) {
            return mActiveAdmin.info.getComponent();
        }
        return null;
    }
    
    void removeActiveAdminLocked(ComponentName adminReceiver) {
        ComponentName cur = getActiveAdminLocked();
        if (cur != null && cur.equals(adminReceiver)) {
            sendAdminCommandLocked(mActiveAdmin,
                    DeviceAdmin.ACTION_DEVICE_ADMIN_DISABLED);
            // XXX need to wait for it to complete.
            mActiveAdmin = null;
        }
    }
    
    public DeviceAdminInfo findAdmin(ComponentName adminName) {
        Intent resolveIntent = new Intent();
        resolveIntent.setComponent(adminName);
        List<ResolveInfo> infos = mContext.getPackageManager().queryBroadcastReceivers(
                resolveIntent, PackageManager.GET_META_DATA);
        if (infos == null || infos.size() <= 0) {
            throw new IllegalArgumentException("Unknown admin: " + adminName);
        }
        
        try {
            return new DeviceAdminInfo(mContext, infos.get(0));
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Bad device admin requested: " + adminName, e);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "Bad device admin requested: " + adminName, e);
            return null;
        }
    }
    
    private static JournaledFile makeJournaledFile() {
        final String base = "/data/system/device_policies.xml";
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    private void saveSettingsLocked() {
        JournaledFile journal = makeJournaledFile();
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(journal.chooseForWrite(), false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, "utf-8");
            out.startDocument(null, true);

            out.startTag(null, "policies");
            
            ActiveAdmin ap = mActiveAdmin;
            if (ap != null) {
                out.startTag(null, "admin");
                out.attribute(null, "name", ap.info.getComponent().flattenToString());
                ap.writeToXml(out);
                out.endTag(null, "admin");
            }
            out.endTag(null, "policies");

            if (mFailedPasswordAttempts != 0) {
                out.startTag(null, "failed-password-attempts");
                out.attribute(null, "value", Integer.toString(mFailedPasswordAttempts));
                out.endTag(null, "failed-password-attempts");
            }
            
            out.endDocument();
            stream.close();
            journal.commit();
        } catch (IOException e) {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                // Ignore
            }
            journal.rollback();
        }
    }

    private void loadSettingsLocked() {
        JournaledFile journal = makeJournaledFile();
        FileInputStream stream = null;
        File file = journal.chooseForRead();
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }
            String tag = parser.getName();
            if (!"policies".equals(tag)) {
                throw new XmlPullParserException(
                        "Settings do not start with policies tag: found " + tag);
            }
            type = parser.next();
            int outerDepth = parser.getDepth();
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                tag = parser.getName();
                if ("admin".equals(tag)) {
                    DeviceAdminInfo dai = findAdmin(
                            ComponentName.unflattenFromString(
                                    parser.getAttributeValue(null, "name")));
                    if (dai != null) {
                        ActiveAdmin ap = new ActiveAdmin(dai);
                        ap.readFromXml(parser);
                        mActiveAdmin = ap;
                    }
                } else if ("failed-password-attempts".equals(tag)) {
                    mFailedPasswordAttempts = Integer.parseInt(
                            parser.getAttributeValue(null, "value"));
                }
            }
        } catch (NullPointerException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (NumberFormatException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (IOException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        long timeMs = getMaximumTimeToLock();
        if (timeMs <= 0) {
            timeMs = Integer.MAX_VALUE;
        }
        try {
            getIPowerManager().setMaximumScreenOffTimeount((int)timeMs);
        } catch (RemoteException e) {
            Log.w(TAG, "Failure talking with power manager", e);
        }
        
    }

    public void systemReady() {
        synchronized (this) {
            loadSettingsLocked();
        }
    }
    
    public void setActiveAdmin(ComponentName adminReceiver) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        DeviceAdminInfo info = findAdmin(adminReceiver);
        if (info == null) {
            throw new IllegalArgumentException("Bad admin: " + adminReceiver);
        }
        synchronized (this) {
            long ident = Binder.clearCallingIdentity();
            try {
                ComponentName cur = getActiveAdminLocked();
                if (cur != null && cur.equals(adminReceiver)) {
                    throw new IllegalStateException("An admin is already set");
                }
                if (cur != null) {
                    removeActiveAdminLocked(adminReceiver);
                }
                mActiveAdmin = new ActiveAdmin(info);
                saveSettingsLocked();
                sendAdminCommandLocked(mActiveAdmin,
                        DeviceAdmin.ACTION_DEVICE_ADMIN_ENABLED);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public ComponentName getActiveAdmin() {
        synchronized (this) {
            return getActiveAdminLocked();
        }
    }
    
    public void removeActiveAdmin(ComponentName adminReceiver) {
        synchronized (this) {
            if (mActiveAdmin == null || mActiveAdmin.getUid() != Binder.getCallingUid()) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.BIND_DEVICE_ADMIN, null);
            }
            long ident = Binder.clearCallingIdentity();
            try {
                removeActiveAdminLocked(adminReceiver);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public void setPasswordMode(ComponentName who, int mode) {
        synchronized (this) {
            if (who == null) {
                throw new NullPointerException("ComponentName is null");
            }
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.passwordMode != mode) {
                ap.passwordMode = mode;
                saveSettingsLocked();
            }
        }
    }
    
    public int getPasswordMode() {
        synchronized (this) {
            return mActiveAdmin != null ? mActiveAdmin.passwordMode
                    : DevicePolicyManager.PASSWORD_MODE_UNSPECIFIED;
        }
    }
    
    public void setMinimumPasswordLength(ComponentName who, int length) {
        synchronized (this) {
            if (who == null) {
                throw new NullPointerException("ComponentName is null");
            }
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.minimumPasswordLength != length) {
                ap.minimumPasswordLength = length;
                saveSettingsLocked();
            }
        }
    }
    
    public int getMinimumPasswordLength() {
        synchronized (this) {
            return mActiveAdmin != null ? mActiveAdmin.minimumPasswordLength : 0;
        }
    }
    
    public boolean isActivePasswordSufficient() {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            return mActivePasswordMode >= getPasswordMode()
                    && mActivePasswordLength >= getMinimumPasswordLength();
        }
    }
    
    public int getCurrentFailedPasswordAttempts() {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_WATCH_LOGIN);
            return mFailedPasswordAttempts;
        }
    }
    
    public void setMaximumFailedPasswordsForWipe(ComponentName who, int num) {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_WIPE_DATA);
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_WATCH_LOGIN);
            if (ap.maximumFailedPasswordsForWipe != num) {
                ap.maximumFailedPasswordsForWipe = num;
                saveSettingsLocked();
            }
        }
    }
    
    public int getMaximumFailedPasswordsForWipe() {
        synchronized (this) {
            return mActiveAdmin != null ? mActiveAdmin.maximumFailedPasswordsForWipe : 0;
        }
    }
    
    public boolean resetPassword(String password) {
        int mode;
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_RESET_PASSWORD);
            mode = getPasswordMode();
            if (password.length() < getMinimumPasswordLength()) {
                return false;
            }
        }
        
        // Don't do this with the lock held, because it is going to call
        // back in to the service.
        long ident = Binder.clearCallingIdentity();
        try {
            LockPatternUtils utils = new LockPatternUtils(mContext);
            utils.saveLockPassword(password, mode);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        
        return true;
    }
    
    public void setMaximumTimeToLock(ComponentName who, long timeMs) {
        synchronized (this) {
            if (who == null) {
                throw new NullPointerException("ComponentName is null");
            }
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_UNLOCK);
            if (ap.maximumTimeToUnlock != timeMs) {
                ap.maximumTimeToUnlock = timeMs;
                
                long ident = Binder.clearCallingIdentity();
                try {
                    saveSettingsLocked();
                    if (timeMs <= 0) {
                        timeMs = Integer.MAX_VALUE;
                    }
                    try {
                        getIPowerManager().setMaximumScreenOffTimeount((int)timeMs);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failure talking with power manager", e);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }
    
    public long getMaximumTimeToLock() {
        synchronized (this) {
            return mActiveAdmin != null ? mActiveAdmin.maximumTimeToUnlock : 0;
        }
    }
    
    public void lockNow() {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_FORCE_LOCK);
            // STOPSHIP need to implement.
        }
    }
    
    void wipeDataLocked(int flags) {
        try {
            RecoverySystem.rebootWipeUserData(mContext);
        } catch (IOException e) {
            Log.w(TAG, "Failed requesting data wipe", e);
        }
    }
    
    public void wipeData(int flags) {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_WIPE_DATA);
            long ident = Binder.clearCallingIdentity();
            try {
                wipeDataLocked(flags);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public void getRemoveWarning(ComponentName comp, final RemoteCallback result) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        synchronized (this) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(comp);
            if (admin == null) {
                try {
                    result.sendResult(null);
                } catch (RemoteException e) {
                }
                return;
            }
            Intent intent = new Intent(DeviceAdmin.ACTION_DEVICE_ADMIN_DISABLE_REQUESTED);
            intent.setComponent(admin.info.getComponent());
            mContext.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        result.sendResult(getResultExtras(false));
                    } catch (RemoteException e) {
                    }
                }
            }, null, Activity.RESULT_OK, null, null);
        }
    }
    
    public void setActivePasswordState(int mode, int length) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        synchronized (this) {
            if (mActivePasswordMode != mode || mActivePasswordLength != length
                    || mFailedPasswordAttempts != 0) {
                long ident = Binder.clearCallingIdentity();
                try {
                    mActivePasswordMode = mode;
                    mActivePasswordLength = length;
                    if (mFailedPasswordAttempts != 0) {
                        mFailedPasswordAttempts = 0;
                        saveSettingsLocked();
                    }
                    sendAdminCommandLocked(DeviceAdmin.ACTION_PASSWORD_CHANGED,
                            DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }
    
    public void reportFailedPasswordAttempt() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        synchronized (this) {
            long ident = Binder.clearCallingIdentity();
            try {
                mFailedPasswordAttempts++;
                saveSettingsLocked();
                int max = getMaximumFailedPasswordsForWipe();
                if (max > 0 && mFailedPasswordAttempts >= max) {
                    wipeDataLocked(0);
                }
                sendAdminCommandLocked(DeviceAdmin.ACTION_PASSWORD_FAILED,
                        DeviceAdminInfo.USES_POLICY_WATCH_LOGIN);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public void reportSuccessfulPasswordAttempt() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        
        synchronized (this) {
            if (mFailedPasswordAttempts != 0) {
                long ident = Binder.clearCallingIdentity();
                try {
                    mFailedPasswordAttempts = 0;
                    saveSettingsLocked();
                    sendAdminCommandLocked(DeviceAdmin.ACTION_PASSWORD_SUCCEEDED,
                            DeviceAdminInfo.USES_POLICY_WATCH_LOGIN);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }
}
