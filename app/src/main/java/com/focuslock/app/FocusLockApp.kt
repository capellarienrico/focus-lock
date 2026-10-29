package com.focuslock.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

class FocusLockApp : Application() {

    lateinit var prefs: BlockPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = BlockPreferences(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private val resumedNonLockActivities = HashSet<Int>()

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                if (activity !is LockActivity) {
                    synchronized(resumedNonLockActivities) {
                        resumedNonLockActivities.add(System.identityHashCode(activity))
                        isNonLockActivityInForeground = resumedNonLockActivities.isNotEmpty()
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity !is LockActivity) {
                    synchronized(resumedNonLockActivities) {
                        resumedNonLockActivities.remove(System.identityHashCode(activity))
                        isNonLockActivityInForeground = resumedNonLockActivities.isNotEmpty()
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity !is LockActivity) {
                    synchronized(resumedNonLockActivities) {
                        resumedNonLockActivities.remove(System.identityHashCode(activity))
                        isNonLockActivityInForeground = resumedNonLockActivities.isNotEmpty()
                    }
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                if (activity !is LockActivity) {
                    synchronized(resumedNonLockActivities) {
                        resumedNonLockActivities.remove(System.identityHashCode(activity))
                        isNonLockActivityInForeground = resumedNonLockActivities.isNotEmpty()
                    }
                }
            }
        })
        AppBlockerService.ensureRunning(this)
    }

    companion object {
        lateinit var instance: FocusLockApp
            private set

        @Volatile
        var isNonLockActivityInForeground: Boolean = false
            private set
    }
}
