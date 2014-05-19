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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryManagerMXBean;
import java.util.List;

import com.sun.management.GarbageCollectorMXBean;
import com.sun.management.GcInfo;


/**
 * cache of information describing the in use garbage collector
 * including its current configuration and state of operation
 */
// package access only
abstract class GCState
{
    private static GCState theGCState = null;
    /**
     * if not already present construct a singleton representing
     * the current in use GC. return the cached singleton
     */
    public static GCState getState()
    {
        if (theGCState == null) {
            // check the manager beans to see if we have a GC we know about
            List<MemoryManagerMXBean> memManagerBeans = ManagementFactory.getMemoryManagerMXBeans();

            for (MemoryManagerMXBean bean : memManagerBeans) {
                String beanName = bean.getName();
                if (beanName.equals("PS Scavenge") || beanName.equals("PS MarkSweep")) {
                    theGCState = new PSState();
                    break;
                } else if (beanName.equals("Copy") || beanName.equals("MarkSweepConpact")) {
                    theGCState = new SerialState();
                    break;
                } else if (beanName.equals("G1 Young Generation") || beanName.equals("G1 Old Generation")) {
                    // theGCState = new G1State();
                    System.out.printf("GCState : cannot run with G1 GC!\n");
                    break;
                } else if (beanName.equals("ParNew") || beanName.equals("ConcurrentMarkSweep")) {
                    System.out.printf("GCState : cannot run with CMS GC!\n");
                    break;
                }
            }
        }

        return theGCState;
    }

    protected GCState()
    {
    }

    /**
     * the memory manager mx bean responsible for young generation collections
     */
    protected MemoryManagerMXBean youngManager;

    /**
     * the memory manager mx bean responsible for old generation collections
     */
    protected MemoryManagerMXBean oldManager;

    /**
     * the gc mx bean implementation responsible for young generation collections
     */
    protected GarbageCollectorMXBean youngGC;

    /**
     * the gc mx bean implementation responsible for old generation collections
     */
    protected GarbageCollectorMXBean oldGC;

    /**
     * the key used to identify the eden memory pool
     */
    protected String edenKey;

    /**
     * the key used to identify the tenured memory pool
     */
    protected String tenuredKey;

    public abstract String getType();

    /**
     * implementation of GCState for Parallel GC
     */
    private static class PSState extends GCState {
        public PSState()
        {
            super();

            List<MemoryManagerMXBean> memManagerBeans = ManagementFactory.getMemoryManagerMXBeans();
            List<java.lang.management.GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

            // look to see which memory manager we are using

            for (MemoryManagerMXBean bean : memManagerBeans) {
                if (bean.getName().equals("PS Scavenge")) {
                    youngManager = bean;
                    edenKey = "PS Eden Space";
                } else if (bean.getName().equals("PS MarkSweep")) {
                    oldManager = bean;
                    tenuredKey = "PS Old Gen";
                }
            }

            for (java.lang.management.GarbageCollectorMXBean bean : gcBeans) {
                if (bean instanceof com.sun.management.GarbageCollectorMXBean) {
                    if (bean.getName().equals("PS Scavenge")) {
                        youngGC = (com.sun.management.GarbageCollectorMXBean)bean;
                    } else if (bean.getName().equals("PS MarkSweep")) {
                        oldGC = (com.sun.management.GarbageCollectorMXBean)bean;
                    }
                }
            }

            if (youngManager == null) {
                System.out.printf("MemoryManager : could not locate young manager\n");
                System.exit(1);
            }
            if (oldManager == null) {
                System.out.printf("MemoryManager : could not locate old manager\n");
                System.exit(1);
            }

            if (youngGC == null) {
                System.out.printf("MemoryManager : could not locate young GC\n");
                System.exit(1);
            }
            if (oldGC == null) {
                System.out.printf("MemoryManager : could not locate old GC\n");
                System.exit(1);
            }
        }

        @Override
        public String getType() {
            return "Parallel Scavenge";
        }
    }

    private static class SerialState extends GCState {
        public SerialState()
        {
            super();

            List<MemoryManagerMXBean> memManagerBeans = ManagementFactory.getMemoryManagerMXBeans();
            List<java.lang.management.GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

            // look to see which memory manager we are using

            for (MemoryManagerMXBean bean : memManagerBeans) {
                if (bean.getName().equals("Copy")) {
                    youngManager = bean;
                    edenKey = "Eden Space";
                } else if (bean.getName().equals("MarkSweepCompact")) {
                    oldManager = bean;
                    tenuredKey = "Tenured Gen";
                }
            }

            for (java.lang.management.GarbageCollectorMXBean bean : gcBeans) {
                if (bean instanceof com.sun.management.GarbageCollectorMXBean) {
                    if (bean.getName().equals("Copy")) {
                        youngGC = (com.sun.management.GarbageCollectorMXBean)bean;
                    } else if (bean.getName().equals("MarkSweepCompact")) {
                        oldGC = (com.sun.management.GarbageCollectorMXBean)bean;
                    }
                }
            }

            if (youngManager == null) {
                System.out.printf("MemoryManager : could not locate young manager\n");
                System.exit(1);
            }
            if (oldManager == null) {
                System.out.printf("MemoryManager : could not locate old manager\n");
                System.exit(1);
            }

            if (youngGC == null) {
                System.out.printf("MemoryManager : could not locate young GC\n");
                System.exit(1);
            }
            if (oldGC == null) {
                System.out.printf("MemoryManager : could not locate old GC\n");
                System.exit(1);
            }
        }

        @Override
        public String getType() {
            return "Serial";
        }
    }

    public long youngCount()
    {
        return youngGC.getCollectionCount();
    }

    public long tenuredCount()
    {
        return oldGC.getCollectionCount();
    }

    public GcInfo lastYoungInfo()
    {
        return youngGC.getLastGcInfo();
    }

    public GcInfo lastTenuredInfo()
    {
        return oldGC.getLastGcInfo();
    }

    public String edenKey()
    {
        return edenKey;
    }

    public String tenuredKey()
    {
        return tenuredKey;
    }
}
