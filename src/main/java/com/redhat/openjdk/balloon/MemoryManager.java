/*
 * Copyright 2014, Red Hat and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 *
 * @authors Andrew Dinn
 */

package com.redhat.openjdk.balloon;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Date;

/**
 * A class responsible for monitoring and managing heap memory usage
 * including creating and deleting balloon in response to low or
 * high pressure on available heap memory.
 *
 * n.b. the current implementation merely monitors memory. it does
 * not attempt to infltae/deflate balloons.
 */
public class MemoryManager
{
    // API called by the native agent code to notify the manager
    // of significant events in the JVM and the agent
    //
    // n.b. this API is entirely private becaise it is only intended
    // to be called into from the native agent code
    //
    // n.b. calls into these methods do not occur directly from JVMTI
    // callback handlers -- for most such callbacks calls into Java
    // code are normally not permitted. The JVMTI callbacks queue
    // events to an asynchronous thread which calls into Java
    // when it is notified of a new event.

    /**
     * called after the agent load to identify details of
     * the GC in use and initalize the various monitoring statistics
     * which will be gathered as the application executes
     */
    private final static boolean init(boolean useSysOut, boolean dumpAll)
    {
        MemoryManager.dumpAll = dumpAll;
        if (useSysOut) {
            out = System.out;
        } else {
            // prepare to log data
            out = openLog();
            if (out == null) {
                return false;
            }
        }

        out.printf("Start: %s\n\n", (new Date()).toString());

        // identify the current GC and heap state
        cacheState();
        if (gcState == null) {
            return false;
        }
        // set up the sample defaults
        for (int i = 0; i < RUNNING_SAMPLE_COUNT; i++) {
            live_running[i] = 0;
            committed_running[i] = 0;
            time_running[i] = i;
        }

        return true;
    }

    /**
     * called every time an end of GC event is notified.
     */
    private final static void gcEnd()
    {
        lastHeapState = currentHeapState;
        currentHeapState = new HeapState(gcState);
        long end = currentHeapState.end();
        long lastEnd;
        long gcPlus;
        long mutatorPlus;
        long totalPlus;
        boolean isFirstGC = (lastHeapState == null);
        boolean isYoungGC = (!isFirstGC && (currentHeapState.youngCount > lastHeapState.youngCount));
        boolean isOldGC = (!isFirstGC && (currentHeapState.oldCount > lastHeapState.oldCount));
        boolean seenOldGC = (currentHeapState.oldCount > 0);
        boolean skippedYoungGCs = (!isFirstGC && (currentHeapState.youngCount > lastHeapState.youngCount + 1));
        long live = 0;
        long committed = 0;
        long max = 0;
        double commPct;
        double livePct;

        if (isFirstGC) {
            // count time up to the last young GC as mutator time
            gcPlus = currentHeapState.youngElapsed();
            totalPlus = end;
            mutatorPlus = totalPlus - gcPlus;
            if (seenOldGC) {
                max = currentHeapState.oldTenuredAfterMax / 1024;
                committed =  currentHeapState.oldTenuredAfterCommitted / 1024;
                live = currentHeapState.oldTenuredAfterSize / 1024;
            } else {
                max = currentHeapState.youngTenuredAfterMax / 1024;
                committed =  currentHeapState.youngTenuredAfterCommitted / 1024;
                live = currentHeapState.youngTenuredAfterSize / 1024;
            }
            commPct = 100D * committed / max;
            livePct = 100D * live / max;
            // don't update lo water until we see an old gen GC
            if (seenOldGC) {
                tenured_committed_lo = committed;
                tenured_live_lo = live;
                tenured_committed_lo_pct = commPct;
                tenured_live_lo_pct = livePct;
            }
            tenured_committed_avge = committed;
            tenured_committed_hi = committed;
            tenured_live_avge = live;
            tenured_live_hi = live;
            tenured_committed_avge_pct = commPct;
            tenured_committed_hi_pct = commPct;
            tenured_live_avge_pct = livePct;
            tenured_live_hi_pct = livePct;
        } else {
            lastEnd = lastHeapState.end();
            // use the latest end time to mark the time interval between last and current
            // we always count a young gc change
            gcPlus = currentHeapState.youngElapsed();
            if (isOldGC) {
                // count the gc time for the old gc
                gcPlus += currentHeapState.oldElapsed();
            }
            // see if we missed any young GCs
            if (skippedYoungGCs) {
                // too bad we cannot make amends for that.
                //System.out.printf("skipped young gc count from %d to %d\n", lastHeapState.youngCount, currentHeapState.youngCount);
            }
            totalPlus = end - lastEnd;
            mutatorPlus =  totalPlus - gcPlus;
            // check the low and high water marks for tenured space
            if (isOldGC) {
                max = currentHeapState.oldTenuredAfterMax / 1024;
                committed =  currentHeapState.oldTenuredAfterCommitted / 1024;
                live = currentHeapState.oldTenuredAfterSize / 1024;
            } else {
                max = currentHeapState.youngTenuredAfterMax / 1024;
                committed= currentHeapState.youngTenuredAfterCommitted / 1024;
                live = currentHeapState.youngTenuredAfterSize / 1024;
            }

            commPct = 100D * committed / max;
            livePct = 100D * live / max;
            // update the committed average and lo water mark using the old tenured sizes after the GC
            if (committed > tenured_committed_hi) {
                tenured_committed_hi = committed;
                tenured_committed_hi_pct = commPct;

            }
            // don't update lo water until we have seen an old GC
            if (seenOldGC) {
                if (committed < tenured_committed_lo || tenured_committed_lo == 0) {
                    tenured_committed_lo = committed;
                    tenured_committed_lo_pct = commPct;
                }
                if (live < tenured_live_lo || tenured_live_lo == 0) {
                    tenured_live_lo = live;
                    tenured_live_lo_pct = livePct;
                }
            }
            if (live > tenured_live_hi) {
                tenured_live_hi = live;
                tenured_live_hi_pct = livePct;
            }
            // now update the averages
            lastEnd = lastHeapState.end();
            tenured_committed_avge = ((tenured_committed_avge * lastEnd) + (committed * (end - lastEnd))) / end;
            tenured_committed_avge_pct = (100D * tenured_committed_avge / max);
            // update the live average and lo/hi water mark using the old tenured size after the GC
            tenured_live_avge = ((tenured_live_avge * lastEnd) + (live * (end - lastEnd))) / end;
            tenured_live_avge_pct = (100D * tenured_live_avge / max);

            // running totals just average the last RUNNING_SAMPLE_COUNT values
            // start with the current committed and live values and current end time and
            // fold in the last RUNNING_SAMPLE_COUNT - 1 values to computer the average
            // then put the current values into the sample set
            long current_time = end;
            long current_committed = committed;
            long current_live = live;
            double live_accumulate = 0.0;
            double committed_accumulate = 0.0;
            long time_accumulate = 0;
            int last = sample_idx + RUNNING_SAMPLE_COUNT; // don't go negative!
            // accumulate RUNNING_SAMPLE_COUNT values
            for (int i = 0; i < RUNNING_SAMPLE_COUNT ; i++) {
                int last_wrap = last % RUNNING_SAMPLE_COUNT;
                long last_time = time_running[last_wrap];
                long delta = (current_time - last_time);
                committed_accumulate += current_committed * delta;
                live_accumulate += current_live * delta;
                time_accumulate += delta;
                current_time = last_time;
                current_committed = committed_running[last_wrap];
                current_live = live_running[last_wrap];
                last--;
            }
            tenured_committed_running_avge = committed_accumulate / time_accumulate;
            tenured_committed_running_avge_pct = 100D * tenured_committed_running_avge / max;
            tenured_live_running_avge = live_accumulate / time_accumulate;
            tenured_live_running_avge_pct = 100D * tenured_live_running_avge / max;

            // move to next sample idx and install current values
            sample_idx = (sample_idx + 1) % RUNNING_SAMPLE_COUNT;
            live_running[sample_idx] = live;
            committed_running[sample_idx] = committed;
            time_running[sample_idx] = end;

            lastHeapState = currentHeapState;
        }
        // ok, we can update the time counters now we don't need the old values

        mutatormsecs += mutatorPlus;
        gcmsecs += gcPlus;
        totalmsecs += totalPlus;

        long dump_delta = (end - timestamp);

        // always dump at first GC
        // dump old GC if last dump was young GC or if last dump was oldGC and was over DUMP_INTERVAL_MIN in the past
        // dump young GC if last dump was over DUMP_INTERVAL_MAX in the past
        if (dumpAll || isFirstGC || (isOldGC && (!dumpedOld || dump_delta > DUMP_INTERVAL_MIN)) || dump_delta > DUMP_INTERVAL_MAX) {
            out.printf("%s timestamp: %9.4f\n", (isOldGC ? "Old: " : "Young: "), totalmsecs/1000.0D);
            currentHeapState.dump(out);
            out.printf("  mutator secs: %9.4f               ", 1.0D * mutatormsecs/1000.0D);
            out.printf("gc secs:      %9.4f\n", 1.0D * gcmsecs/1000.0D);
            out.printf("  live:         %9d               ", live);
            out.printf("committed:    %9d\n", committed);
            out.printf(  "  live hi:      %9d (%7.4f%%)    ", (long)tenured_live_hi, tenured_live_hi_pct);
            out.printf("live lo:      %9d (%7.4f%%)\n", (long)tenured_live_lo, tenured_live_lo_pct);
            out.printf("  live avg:     %9d (%7.4f%%)    ", (long)tenured_live_avge, tenured_live_avge_pct);
            out.printf("(last %2d):    %9d (%7.4f%%)\n", RUNNING_SAMPLE_COUNT, (long)tenured_live_running_avge, tenured_live_running_avge_pct);
            out.printf("  commit hi:    %9d (%7.4f%%)    ", (long)tenured_committed_hi, tenured_committed_hi_pct);
            out.printf("commit lo:    %9d (%7.4f%%)\n", (long)tenured_committed_lo, tenured_committed_lo_pct);
            out.printf("  commit avg:   %9d (%7.4f%%)    ", (long)tenured_committed_avge, tenured_committed_avge_pct);
            out.printf("(last %2d):    %9d (%7.4f%%)\n", RUNNING_SAMPLE_COUNT, (long)tenured_committed_running_avge, tenured_committed_running_avge_pct);
            out.println();

            timestamp = end;
            dumpedOld = isOldGC;
        }
    }

    /**
     * called when the agent is terminated.
     */

    private final static void terminate()
    {
        // identify the current GC state
    }

    /**
     * output stream to the ballon stats log file or Syste.out if useSysout was passed as true
     */
    private static PrintStream out = null;

    /**
     *  flag passed in by agent as true if data should be dumped at every GC or
     *  at infrequent intervals
     */
    private static boolean dumpAll;
    /**
     * accessor for the GC satistics
     */
    private static GCState gcState = null;
    /**
     * heap stats for the most recently recorded GC
     */
    private static HeapState currentHeapState = null;
    /**
     * heap stats for the previous most recently recorded GC
     */
    private static HeapState lastHeapState = null;

    /**
     * the total time spent in GC in millisecs
     */
    private static long gcmsecs;

    /**
     * the total time not spent in GC millisecs
     */
    private static long mutatormsecs;

    /**
     * the total execution time in millisecs
     */
    private static long totalmsecs;

    /**
     * high water mark for tenured live heap as a percentage of maximum
     */
    private static double tenured_live_hi_pct = 0D;
    /**
     * low water mark for tenured live heap as a percentage of maximum
     */
    private static double tenured_live_lo_pct = 0D;
    /**
     * average tenured live heap as a percentage of maximum
     */
    private static double tenured_live_avge_pct = 0D;
    /**
     * running average tenured live heap as a percentage of maximum
     */
    private static double tenured_live_running_avge_pct = 0D;

    /**
     * high water mark for tenured live heap
     */
    private static double tenured_live_hi = 0D;
    /**
     * low water mark for tenured live heap
     */
    private static double tenured_live_lo = 0D;
    /**
     * average tenured live heap
     */
    private static double tenured_live_avge = 0D;
    /**
     * running average tenured live heap
     */
    private static double tenured_live_running_avge = 0D;

    /**
     * high water mark for tenured committed heap as a percentage of maximum
     */
    private static double tenured_committed_hi_pct = 0D;
    /**
     * low water mark for tenured committed heap as a percentage of maximum
     */
    private static double tenured_committed_lo_pct = 0D;
    /**
     * average tenured committed heap as a percentage of maximum
     */
    private static double tenured_committed_avge_pct = 0D;
    /**
     * running average tenured committed heap as a percentage of maximum
     */
    private static double tenured_committed_running_avge_pct = 0D;

    /**
     * high water mark for tenured committed heap
     */
    private static double tenured_committed_hi = 0D;
    /**
     * low water mark for tenured committed heap
     */
    private static double tenured_committed_lo = 0D;
    /**
     * average tenured committed heap
     */
    private static double tenured_committed_avge = 0D;
    /**
     * running average tenured committed heap
     */
    private static double tenured_committed_running_avge = 0D;

    /**
     *  number of samples used to keep a running average of the committed and live sizes
     */
    private final static int RUNNING_SAMPLE_COUNT = 10;

    /**
     *  the last N values for committed
     */
    private static long[] committed_running = new long[RUNNING_SAMPLE_COUNT];

    /**
     *  the last N values for live
     */
    private static long[] live_running = new long[RUNNING_SAMPLE_COUNT];

    /**
     *  the last N time intervals over which samples are valid
     */
    private static long[] time_running = new long[RUNNING_SAMPLE_COUNT];

    /**
     * round robin counter for latest running stat sample index
     */
    private static int sample_idx = RUNNING_SAMPLE_COUNT - 1;

    /**
     * was the last dump for an old GC?
     *
     * used to ensure we don't skip an old GC dump because of a young GC dump
     */
    private static boolean dumpedOld = false;

    /**
     * minimum interval between old GCs where we will dump stats
     *
     * i.e. if old GCs come thick and fast we wll wait at least 20 seconds
     * before dumping details of the next old GC
     *
     * n.b. only respected if dumpAll is false
     */
    private static long DUMP_INTERVAL_MIN = 20 * 1000;
    /**
     * maximum interval between young and or old GCs where we will dump stats
     *
     * i.e. if old GCs are thin in the ground we will dump details of the next
     * young GC if we have not seen an old GC for 2 minutes
     *
     * n.b. only respected if dumpAll is false
     */
    private static long DUMP_INTERVAL_MAX = 120 * 1000;

    /**
     * timestamp of last GC for which we dumped stats in msecs
     *
     * inital value ensures we dump the first old GC
     */
    private static long timestamp = - DUMP_INTERVAL_MIN;

    /**
     * called during init to open the log file and write a header
     */
    private static PrintStream openLog()
    {
        File file = new File(".balloonstats.log");
        try {
            FileOutputStream fos = new FileOutputStream(file, true);
            PrintStream out = new PrintStream(fos, true);
            return out;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /**
     * called during init to cache accessors for GC data
     */
    private static void cacheState()
    {
        gcState = GCState.getState();
        gcmsecs = mutatormsecs = 0;
    }
}
