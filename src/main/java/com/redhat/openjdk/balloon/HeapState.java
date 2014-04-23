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

import com.sun.management.GcInfo;

import java.io.PrintStream;
import java.lang.management.MemoryUsage;

/**
 * cache of information describing the Java heap state as
 * at some specific GC point
 */

// package access only
class HeapState
{
    /**
     * the GC count for the young gen collector
     */
    public long youngCount;

    /**
     * the collection end time for the young gen collector
     */
    public long youngEndTime;

    /**
     * the collection elapsed time for the young gen collector
     */
    public long youngElapsedTime;

    /**
     * the size of the eden pool in bytes before the young gen collector ran
     */
    public long youngEdenBeforeSize;

    /**
     * the committed space in the eden pool in bytes before the young gen collector ran
     */
    public long youngEdenBeforeCommitted;

    /**
     * the available space in the eden pool in bytes before the young gen collector ran
     */
    public long youngEdenBeforeMax;

    /**
     * the size of the tenured pool in bytes before the young gen collector ran
     */
    public long youngTenuredBeforeSize;

    /**
     * the committed space in the tenured pool in bytes before the young gen collector ran
     */
    public long youngTenuredBeforeCommitted;

    /**
     * the available space in the tenured pool in bytes before the young gen collector ran
     */
    public long youngTenuredBeforeMax;

    /**
     * the size of the eden pool in bytes after the young gen collector ran
     */
    public long youngEdenAfterSize;

    /**
     * the committed space in the eden pool in bytes after the young gen collector ran
     */
    public long youngEdenAfterCommitted;

    /**
     * the available space in the eden pool in bytes after the young gen collector ran
     */
    public long youngEdenAfterMax;

    /**
     * the size of the tenured pool in bytes after the young gen collector ran
     */
    public long youngTenuredAfterSize;

    /**
     * the available space in the tenured pool in bytes after the young gen collector ran
     */
    public long youngTenuredAfterCommitted;

    /**
     * the available space in the tenured pool in bytes after the young gen collector ran
     */
    public long youngTenuredAfterMax;

    /**
     * the GC count for the tenured gen collector
     */
    public long oldCount;

    /**
     * the collection start time for the tenured gen collector
     */
    public long oldEndTime;

    /**
     * the collection elapsed time for the tenured gen collector
     */
    public long oldElapsedTime;

    /**
     * the size of the eden pool in bytes before the tenured gen collector ran
     */
    public long oldEdenBeforeSize;

    /**
     * the committed space in the eden pool in bytes before the young gen collector ran
     */
    public long oldEdenBeforeCommitted;

    /**
     * the available space in the eden pool in bytes before the young gen collector ran
     */
    public long oldEdenBeforeMax;

    /**
     * the size of the tenured pool in bytes before the tenured gen collector ran
     */
    public long oldTenuredBeforeSize;

    /**
     * the committed space in the tenured pool in bytes before the young gen collector ran
     */
    public long oldTenuredBeforeCommitted;

    /**
     * the available space in the tenured pool in bytes before the young gen collector ran
     */
    public long oldTenuredBeforeMax;

    /**
     * the size of the eden pool in bytes after the tenured gen collector ran
     */
    public long oldEdenAfterSize;

    /**
     * the commited space in the eden pool in bytes after the young gen collector ran
     */
    public long oldEdenAfterCommitted;

    /**
     * the available space in the eden pool in bytes after the young gen collector ran
     */
    public long oldEdenAfterMax;

    /**
     * the size of the tenured pool in bytes after the tenured gen collector ran
     */
    public long oldTenuredAfterSize;

    /**
     * the committed space in the tenured pool in bytes after the young gen collector ran
     */
    public long oldTenuredAfterCommitted;

    /**
     * the available space in the tenured pool in bytes after the young gen collector ran
     */
    public long oldTenuredAfterMax;

    /**
     * construct a heap state which caches the current GC state
     * @param gcState
     */
    public HeapState(GCState gcState)
    {
        GcInfo youngInfo = gcState.lastYoungInfo();
        GcInfo tenuredInfo = gcState.lastTenuredInfo();
        String edenKey = gcState.edenKey();
        String tenuredKey = gcState.tenuredKey();
        youngCount = gcState.youngCount();
        oldCount = gcState.tenuredCount();

        if (youngInfo != null) {
            youngEndTime = youngInfo.getEndTime();
            youngElapsedTime = youngInfo.getDuration();
            MemoryUsage before = youngInfo.getMemoryUsageBeforeGc().get(edenKey);
            MemoryUsage after = youngInfo.getMemoryUsageAfterGc().get(edenKey);
            if (before != null) {
                youngEdenBeforeSize = before.getUsed();
                youngEdenBeforeCommitted= before.getCommitted();
                youngEdenBeforeMax = before.getMax();
            } else {
                youngEdenBeforeSize = 0L;
                youngEdenBeforeCommitted = 0L;
                youngEdenBeforeMax = 0L;
            }
            if (after != null) {
                youngEdenAfterSize = after.getUsed();
                youngEdenAfterCommitted = after.getCommitted();
                youngEdenAfterMax = after.getMax();
            } else {
                youngEdenAfterSize = 0L;
                youngEdenAfterCommitted = 0L;
                youngEdenAfterMax = 0L;
            }
            before = youngInfo.getMemoryUsageBeforeGc().get(tenuredKey);
            after = youngInfo.getMemoryUsageAfterGc().get(tenuredKey);
            if (before != null) {
                youngTenuredBeforeSize = before.getUsed();
                youngTenuredBeforeCommitted = before.getCommitted();
                youngTenuredBeforeMax = before.getMax();
            } else {
                youngTenuredBeforeSize = 0L;
                youngTenuredBeforeCommitted = 0L;
                youngTenuredBeforeMax = 0L;
            }
            if (after != null) {
                youngTenuredAfterSize = after.getUsed();
                youngTenuredAfterCommitted = after.getCommitted();
                youngTenuredAfterMax = after.getMax();
            } else {
                youngTenuredAfterSize = 0L;
                youngTenuredAfterCommitted = 0L;
                youngTenuredAfterMax = 0L;
            }
        } else {
            youngEndTime = 0L;
            youngElapsedTime = 0L;
            youngEdenBeforeSize = 0L;
            youngEdenBeforeCommitted = 0L;
            youngEdenBeforeMax = 0L;
            youngTenuredBeforeSize = 0L;
            youngTenuredBeforeCommitted = 0L;
            youngTenuredBeforeMax = 0L;
            youngEdenAfterSize = 0L;
            youngEdenAfterCommitted = 0L;
            youngEdenAfterMax = 0L;
            youngTenuredAfterSize = 0L;
            youngTenuredAfterCommitted = 0L;
            youngTenuredAfterMax = 0L;
        }

        if (tenuredInfo != null) {
            oldEndTime = tenuredInfo.getEndTime();
            oldElapsedTime = tenuredInfo.getDuration();
            MemoryUsage before = tenuredInfo.getMemoryUsageBeforeGc().get(edenKey);
            MemoryUsage after = tenuredInfo.getMemoryUsageAfterGc().get(edenKey);
            if (before != null) {
                oldEdenBeforeSize = before.getUsed();
                oldEdenBeforeCommitted = before.getCommitted();
                oldEdenBeforeMax = before.getMax();
            } else {
                oldEdenBeforeSize = 0L;
                oldEdenBeforeCommitted = 0L;
                oldEdenBeforeMax = 0L;
            }
            if (after != null) {
                oldEdenAfterSize = after.getUsed();
                oldEdenAfterCommitted = after.getCommitted();
                oldEdenAfterMax = after.getMax();
            } else {
                oldEdenAfterSize = 0L;
                oldEdenAfterCommitted = 0L;
                oldEdenAfterMax = 0L;
            }
            before = tenuredInfo.getMemoryUsageBeforeGc().get(tenuredKey);
            after = tenuredInfo.getMemoryUsageAfterGc().get(tenuredKey);
            if (before != null) {
                oldTenuredBeforeSize = before.getUsed();
                oldTenuredBeforeCommitted = before.getCommitted();
                oldTenuredBeforeMax = before.getMax();
            } else {
                oldTenuredBeforeSize = 0L;
                oldTenuredBeforeCommitted = 0L;
                oldTenuredBeforeMax = 0L;
            }
            if (after != null) {
                oldTenuredAfterSize = after.getUsed();
                oldTenuredAfterCommitted = after.getCommitted();
                oldTenuredAfterMax = after.getMax();
            } else {
                oldTenuredAfterSize = 0L;
                oldTenuredAfterCommitted = 0L;
                oldTenuredAfterMax = 0L;
            }
        } else {
            oldEndTime = 0L;
            oldElapsedTime = 0L;
            oldEdenBeforeSize = 0L;
            oldEdenBeforeCommitted = 0L;
            oldEdenBeforeMax = 0L;
            oldTenuredBeforeSize = 0L;
            oldTenuredBeforeCommitted = 0L;
            oldTenuredBeforeMax = 0L;
            oldEdenAfterSize = 0L;
            oldEdenAfterCommitted = 0L;
            oldEdenAfterMax = 0L;
            oldTenuredAfterSize = 0L;
            oldTenuredAfterCommitted = 0L;
            oldTenuredAfterMax = 0L;
        }
    }
    public long youngStart() { return youngEndTime - youngElapsedTime; }
    public long youngElapsed() { return youngElapsedTime; }
    public long youngEnd() { return youngEndTime; }

    public long oldStart() { return oldEndTime - oldElapsedTime; }
    public long oldElapsed() { return oldElapsedTime; }
    public long oldEnd() { return oldEndTime; }

    public long end() { return (oldEndTime > youngEndTime ? oldEndTime : youngEndTime); }

    public void dump()
    {
        dump(System.out);
    }

    public void dump(PrintStream out)
    {
        StringBuilder builder = new StringBuilder();
        dump(builder);
        out.print(builder.toString());
    }

    public void dump(StringBuilder builder)
    {
        dumpCountTime(builder, "  young count: ", youngCount, "  young msecs: ", youngElapsedTime);
        dumpUsage(builder, "eden", youngEdenBeforeSize, youngEdenBeforeCommitted, youngEdenBeforeMax,
                youngEdenAfterSize, youngEdenAfterCommitted, youngEdenAfterMax);
        dumpUsage(builder, "tenured", youngTenuredBeforeSize, youngEdenBeforeCommitted, youngTenuredBeforeMax,
                youngTenuredAfterSize, youngTenuredAfterCommitted, youngTenuredAfterMax);
        dumpCountTime(builder, "  old count:   ", oldCount, "  old msecs:   ", oldElapsedTime);
        dumpUsage(builder, "eden", oldEdenBeforeSize, oldEdenBeforeCommitted, oldEdenBeforeMax,
                oldEdenAfterSize, oldEdenAfterCommitted, oldEdenAfterMax);
        dumpUsage(builder, "tenured", oldTenuredBeforeSize, oldTenuredBeforeCommitted, oldTenuredBeforeMax,
                oldTenuredAfterSize, oldTenuredAfterCommitted, oldTenuredAfterMax);
    }
    void dumpCountTime(StringBuilder builder, String tag1, long count, String tag2, long msecs)
    {
        builder.append(tag1);
        builder.append(count);
        builder.append('\n');
        builder.append(tag2);
        builder.append(1.0D * msecs/1000.0D);
        builder.append('\n');
    }
    void dumpUsage(StringBuilder builder, String tag, long beforeSize, long beforeCommitted, long beforeMax, long afterSize, long afterCommitted, long afterMax)
    {
        builder.append("    ");
        builder.append(tag);
        builder.append(": ");
        builder.append(beforeSize/1024);
        builder.append("KB/");
        builder.append(beforeCommitted/1024);
        builder.append("KB(");
        builder.append(beforeMax/1024);
        builder.append("KB) --> ");
        builder.append(afterSize/1024);
        builder.append("KB/");
        builder.append(afterCommitted/1024);
        builder.append("KB(");
        builder.append(afterMax/1024);
        builder.append("KB)\n");
    }
}
