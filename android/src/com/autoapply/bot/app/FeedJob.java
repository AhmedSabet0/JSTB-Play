package com.autoapply.bot.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** About every hour, with the app closed too: new jobs from LinkedIn and Wuzzuf for your job titles (Feed), so the
 *  Jobs tab is fresh when you open it. Android runs it when there is a network and may move it a little to save battery. */
public final class FeedJob extends JobService {
    static final int ID = 4107;

    /** Asks Android for the hourly run (once is enough: it stays until the app is uninstalled or the phone restarts,
     *  and the app asks again on every start). */
    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            for (JobInfo j : js.getAllPendingJobs()) {
                if (j.getId() == ID) {
                    return;   // already planned
                }
            }
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, FeedJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60 * 1000L)
                    .setPersisted(false)
                    .build());
        } catch (RuntimeException e) {
            // no background runs on this phone: the feed still runs while the app is open
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final AndroidPlatform p = AndroidPlatform.get(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (p.engine.account.signedIn()) {
                        p.engine.feed.runIfDue();
                    }
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "feed-job").start();
        return true;   // working on another thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // Android stopped it early: try again later
    }
}
