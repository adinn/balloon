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

import java.util.Stack;

/**
 * Class which allocates and deallocates balloon objects
 * in order to fill up heap space with pages which are
 * then unmapped from physical memory.
 */
// package access only
class BalloonManager
{
    // useful units
    public final static int KBs = 1024;
    public final static int MBs = KBs * 1024;
    public final static int BALLOON_SIZE = 1 * MBs;

    // a private wrapper for the byte arrays we want to map and demap
    private static class Balloon {

        public byte[] data;
        public Balloon() {
            data = new byte[BALLOON_SIZE];
        }
    }

    // the list of balloons we currently have allocated
    private final static Stack<Balloon> balloons = new Stack<Balloon>();

    /**
     * Register a balloon memory buffer
     * @param buffer
     * @return true if a GC occurred while registering the balloon ow false
     */
    private static native boolean registerBalloon(byte[] buffer);

    /**
     * Unregister a balloon memory buffer
     * @param buffer
     * @return true if a GC occurred while registering the balloon ow false
     */
    private static native boolean unregisterBalloon(byte[] buffer);

    /**
     * Create a balloon on the java heap and register it,
     * n.b. package-private access
     */
    static boolean createBalloon() {
        Balloon balloon = new Balloon();
        boolean result = registerBalloon(balloon.data);
        if (!result) {
            balloons.push(balloon);
        }
        return result;
    }


    /**
     * Remove a balloon making it's data area available for reuse
     * n.b. package-private access
     */
    static boolean deleteBalloon() {
        if(balloons.size() == 0) {
            // should not happen
            return false;
        }
        Balloon balloon = balloons.firstElement();
        boolean result = unregisterBalloon(balloon.data);
        if (!result) {
            balloons.pop();
        }
        return result;
    }
}
