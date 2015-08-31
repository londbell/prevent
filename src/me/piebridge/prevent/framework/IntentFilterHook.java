package me.piebridge.prevent.framework;

import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.net.Uri;
import android.os.Binder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import me.piebridge.forcestopgb.BuildConfig;
import me.piebridge.prevent.common.GmsUtils;
import me.piebridge.prevent.common.PackageUtils;
import me.piebridge.prevent.framework.util.AlarmManagerServiceUtils;
import me.piebridge.prevent.framework.util.BroadcastFilterUtils;
import me.piebridge.prevent.framework.util.LogUtils;
import me.piebridge.prevent.framework.util.NotificationManagerServiceUtils;
import me.piebridge.prevent.framework.util.SafeActionUtils;
import me.piebridge.prevent.xposed.XposedMod;

/**
 * Created by thom on 15/8/11.
 */
public class IntentFilterHook {

    private static Context mContext;
    private static AccountWatcher accountWatcher;
    private static Map<String, Boolean> mPreventPackages;
    private static ScheduledThreadPoolExecutor checkingExecutor = new ScheduledThreadPoolExecutor(0x2);
    private static final Object CHECKING_LOCK = new Object();
    private static Map<String, ScheduledFuture<?>> checkingFutures = new HashMap<String, ScheduledFuture<?>>();

    private IntentFilterHook() {

    }

    public static void setContext(Context context, Map<String, Boolean> preventPackages) {
        mPreventPackages = preventPackages;
        accountWatcher = new AccountWatcher(context);
        mContext = context;
    }

    public static boolean canHook(int result) {
        return result > 0 && SystemHook.isSystemHook() && mContext != null;
    }

    public static IntentFilterMatchResult hookAfterMatch(Object filter, Object[] args) {
        String action = (String) args[0];
        if (filter instanceof PackageParser.ActivityIntentInfo) {
            return hookActivityIntentInfo((PackageParser.ActivityIntentInfo) filter, XposedMod.BROADCAST_SENDER.get(), action);
        } else if (filter instanceof PackageParser.ServiceIntentInfo) {
            return hookServiceIntentInfo((PackageParser.ServiceIntentInfo) filter, XposedMod.SERVICE_SENDER.get(), action);
        } else if (BroadcastFilterUtils.isBroadcastFilter(filter)) {
            return hookBroadcastFilter(filter, args);
        }

        return IntentFilterMatchResult.NONE;
    }

    private static IntentFilterMatchResult hookBroadcastFilter(Object filter, Object[] args) {
        String action = (String) args[0];
        if (NotificationManagerServiceUtils.canHook(filter, action)) {
            return NotificationManagerServiceUtils.hook((Uri) args[0x3], mPreventPackages);
        } else if (AlarmManagerServiceUtils.canHook(args)) {
            return AlarmManagerServiceUtils.hook(filter);
        } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
            return hookCloseSystemDialogs(filter, action);
        }
        return IntentFilterMatchResult.NONE;
    }

    private static IntentFilterMatchResult hookCloseSystemDialogs(Object filter, String action) {
        String packageName = BroadcastFilterUtils.getPackageName(filter);
        if (packageName != null && mPreventPackages.containsKey(packageName)) {
            LogUtils.logIntentFilter(true, filter, action, packageName);
            return IntentFilterMatchResult.NO_MATCH;
        }
        return IntentFilterMatchResult.NONE;
    }

    private static IntentFilterMatchResult allowSafeIntent(PackageParser.ActivityIntentInfo filter, String action, String packageName) {
        LogUtils.logIntentFilterWarning(false, filter, action, packageName);
        if (Boolean.TRUE.equals(mPreventPackages.get(packageName))) {
            PreventLog.w("allow " + packageName + " for next service/broadcast");
            mPreventPackages.put(packageName, false);
            disallowLater(packageName);
        }
        return IntentFilterMatchResult.NONE;
    }

    private static void disallowLater(final String packageName) {
        synchronized (CHECKING_LOCK) {
            ScheduledFuture<?> checkingFuture = checkingFutures.get(packageName);
            if (checkingFuture != null && checkingFuture.getDelay(TimeUnit.SECONDS) > 0) {
                checkingFuture.cancel(false);
            }
            checkingFuture = checkingExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    int count = SystemHook.getSystemReceiver().countCounter(packageName);
                    if (count == 0 && Boolean.FALSE.equals(mPreventPackages.get(packageName))) {
                        mPreventPackages.put(packageName, true);
                    }
                }
            }, SystemHook.TIME_CHECK_DISALLOW, TimeUnit.SECONDS);
            checkingFutures.put(packageName, checkingFuture);
        }
    }

    private static IntentFilterMatchResult hookActivityIntentInfo(PackageParser.ActivityIntentInfo filter, String sender, String action) {
        // for receiver, we don't block for activity
        PackageParser.Activity activity = filter.activity;
        PackageParser.Package owner = activity.owner;
        if (!owner.receivers.contains(activity)) {
            // we only care about receiver
            return IntentFilterMatchResult.NONE;
        }
        ApplicationInfo ai = owner.applicationInfo;
        String packageName = ai.packageName;
        ComponentName cn = new ComponentName(packageName, activity.className);
        if (SafeActionUtils.isSafeAction(mContext, action, cn)) {
            return allowSafeIntent(filter, action, packageName);
        }
        if (canNotHook(filter, action, ai)) {
            return IntentFilterMatchResult.NONE;
        }
        if (sender == null && !AppGlobals.getPackageManager().isProtectedBroadcast(action)) {
            LogUtils.logIntentFilterWarning(false, filter, action, packageName);
            return allowSafeIntent(filter, action, packageName);
        }
        LogUtils.logIntentFilter(true, filter, action, packageName);
        return IntentFilterMatchResult.NO_MATCH;
    }

    private static boolean canNotHook(Object filter, String action, ApplicationInfo ai) {
        boolean prevents = Boolean.TRUE.equals(mPreventPackages.get(ai.packageName));
        return !prevents || (GmsUtils.GMS.equals(ai.packageName) && canUseGmsForDependency(filter, action));
    }

    private static IntentFilterMatchResult hookServiceIntentInfo(PackageParser.ServiceIntentInfo filter, String sender, String action) {
        PackageParser.Service service = filter.service;
        PackageParser.Package owner = service.owner;
        ApplicationInfo ai = owner.applicationInfo;
        if (canNotHook(filter, action, ai)) {
            return IntentFilterMatchResult.NONE;
        }
        String packageName = ai.packageName;
        PreventLog.v("action: " + action + ", filter: " + filter + ", packageName: " + packageName + ", sender: " + sender);
        if (!isSystemOrSelf(packageName, sender) && !accountWatcher.canNotHook(action, packageName)) {
            // for call from non-system, accept account
            LogUtils.logIntentFilterWarning(true, filter, action, ai.packageName);
            return IntentFilterMatchResult.NO_MATCH;
        }
        LogUtils.logIntentFilter(false, filter, action, packageName);
        return IntentFilterMatchResult.NONE;
    }

    private static boolean isSystemOrSelf(String packageName, String sender) {
        return (sender == null && Binder.getCallingUid() == android.os.Process.SYSTEM_UID) ||
                (sender != null && packageName.equals(sender));
    }

    private static boolean canUseGmsForDependency(Object filter, String action) {
        int callingUid = Binder.getCallingUid();
        PackageManager pm = mContext.getPackageManager();
        String[] callingPackageNames = pm.getPackagesForUid(callingUid);
        if (callingPackageNames == null) {
            return false;
        }
        for (String callingPackageName : callingPackageNames) {
            if (GmsUtils.isGapps(pm, callingPackageName)) {
                if (BuildConfig.DEBUG) {
                    PreventLog.v("allow " + callingPackageName + " to use gms if needed, filter:  " + filter + ", action: " + action);
                } else if (!isSystemPackage(pm, callingPackageName)) {
                    PreventLog.d("allow " + callingPackageName + " to use gms if needed, action: " + action);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isSystemPackage(PackageManager pm, String packageName) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return PackageUtils.isSystemPackage(ai.flags);
        } catch (PackageManager.NameNotFoundException e) {
            PreventLog.d("cannot find package " + packageName, e);
            return false;
        }
    }

    public static void onPackageAdded() {
        if (accountWatcher != null) {
            accountWatcher.updateAuthDescriptions();
        }
    }

}
