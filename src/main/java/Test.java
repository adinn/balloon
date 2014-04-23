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

import java.util.Random;

public class Test
{
    static int TEST_MAX = 100;
    final static int SMALL = 0;
    final static int MEDIUM = 1;
    final static int LARGE = 2;
    static long byte_count = 0;
    static int small_count = 0;
    static int medium_count = 0;
    static int large_count = 0;
    final static Random random = new Random();

    byte[] data;
    int tag;

    static public void main(String[] args)
    {
        long result = 0L;
        int idx = 0;
        long counter = 0;
        int promote_count = 1000;
        long workunit = 100;
        boolean have_tier3 = false;
        if (args.length > 0) {
            if (args.length > 2)  {
                System.out.println("usage : Test [size [workunit]]");
                System.out.println("        where size     >= 1, default 1 (100s of retained items)");
                System.out.println("              workunit >= 0, default 100 (nanosecs)");
                System.exit(1);
            }
            int size = Integer.valueOf(args[0]);
            if (size < 1) {
                System.out.println("invalid size : " + args[0]);
                System.out.println("usage : Test [size [workunit]]");
                System.out.println("        where size     >= 1, default 1 (100s of retained items)");
                System.out.println("              workunit >= 0, default 100 (nanosecs)");
            }
            TEST_MAX = TEST_MAX * size;
            // adjust promote count so we promote at roughly the same rate
            promote_count = promote_count / size;
            if (promote_count < 2) {
                promote_count = 2;
            }
            if (args.length > 1) {
                workunit = Integer.valueOf(args[1]);
                if (workunit < 0) {
                    System.out.println("invalid workunit : " + args[0]);
                    System.out.println("usage : Test [size [workunit]]");
                    System.out.println("        where size     >= 1, default 1 (100s of retained items)");
                    System.out.println("              workunit >= 0, default 100 (nanosecs)");
                }
            }
        }
        Test[] retained = new Test[TEST_MAX];
        Test[] retained2 = new Test[TEST_MAX];
        Test[] retained3 = new Test[TEST_MAX * 2];

        // we promote into retained2 on 1 out of promote_count cycles
        // and we always promote on the first round

        boolean promote = true;

        do {
            long rand = random.nextLong();
            if (rand < 0) {
                rand = Long.MIN_VALUE - rand;
            }
            double skew = 1.0D * rand / Long.MAX_VALUE;
            Test old_test = retained[idx];
            Test new_test = new Test(skew);
            retained[idx] = new_test;
            new_test.increment();
            if (old_test != null) {
                old_test.decrement();
            }
            if (promote) {
                old_test = retained2[idx];
                new_test = retained[idx];
                retained2[idx] = new_test;
                retained[idx] = null;
                if (old_test != null) {
                    old_test.decrement();
                }
            }

            counter++;
            idx = (idx + 1);

            if (idx >= TEST_MAX) {
                idx = 0;
            }

            if (idx == 0) {
                // decide whether to promote on next cycle
                promote = (skew > (promote_count - 1) / promote_count);

                // decide whether to retain an extra data set
                // every 1 in 50000 loops we add a tier 3
                // every 1 in 10000 loops we remove tier 3

                if (!have_tier3 && (skew < 0.00002)) {
                    // promote tier1 and tier2 to tier3
                    for (int i = 0; i < TEST_MAX; i++) {
                        if (retained3[idx] != null) {
                            // hmm, should not happen
                            retained3[idx].decrement();
                        }
                        retained3[idx] = retained[idx];
                        retained[idx] = null;
                    }
                    for (int i = TEST_MAX; i < 2 * TEST_MAX; i++) {
                        if (retained3[idx] != null) {
                            // hmm, should not happen
                            retained3[idx].decrement();
                        }
                        retained3[idx] = retained2[idx];
                        retained2[idx] = null;
                    }
                    have_tier3 = true;
                } else if (have_tier3 && (skew < 0.0001)) {
                    // clear tier3
                    for (int i = 0; i < TEST_MAX * 2; i++) {
                        if (retained3[idx] != null) {
                            retained3[idx].decrement();
                        }
                        retained3[idx] = null;
                    }
                    have_tier3 = false;
                }
            }
            // dump stats every 100 cycles
            if (counter % (TEST_MAX * 100L) == 0) {
                // System.out.println("byte_count == " + byte_count + " count = " + (small_count + medium_count + large_count) +" (s: " + small_count + " m: " + medium_count + " l: " + large_count + ")");
            }
            long nanostart = System.nanoTime();
            long nanoend;
            do {
                Test t =  (skew > 0.5 ? retained[idx] : retained2[idx]);
                result += (t != null ? t.size() : -5);
                nanoend = System.nanoTime();
            } while ((nanoend - nanostart) < workunit);
            if (counter == Long.MAX_VALUE) {
                System.out.printf("bingo %d\n", result);
            }
        } while(true);
    }

    public Test(double skew)
    {
        int size;
        int factor = (int)(skew * 10000D);
        if (factor < 25) {
            size = 4 * 1024;
            size += (int)(size * skew);
            tag = MEDIUM;
        } else if (factor > 9997) {
            size = 8 * 1024 * 1024;
            size = (int)(size * skew);
            tag = LARGE;
        } else {
            size = 48;
            size += (int)(size * skew / 10);
            tag = SMALL;
        }

        data = new byte[size];
    }

    public int size()
    {
        return 32 + data.length;
    }

    public void increment()
    {
        byte_count += size();
        switch(tag) {
        case SMALL:
            small_count++;
            break;
        case MEDIUM:
            medium_count++;
            break;
        case LARGE:
            large_count++;
            break;
        }
    }

    public void decrement()
    {
        byte_count -= size();
        switch(tag) {
        case SMALL:
            small_count--;
            break;
        case MEDIUM:
            medium_count--;
            break;
        case LARGE:
            large_count--;
            break;
        }
    }
}
