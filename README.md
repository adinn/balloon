A Java native agent used to regularly dump stats on memory use in Java
applications. This agent maintains various running statistics on
operaton of the GC in any Java application it is loaded into.

By default stats are appended to file .balloonstats.log. but thye can
also be redirected to System.out.

Stats will normally be dumped at each GC but the agent imposes certain
limits on dump frequency to ensure they do not occur too frequently or
too infrequently. Old GC stats will not be dumped if an old GC
happened within the previous 20 seconds. Young GC stats will not be
dumped unless 60 seconds have elapsed since the last old or young GC
dump.

Stats listed at each dump ar eas follows

GC Type (Old/Young) and timestamp
  last young GC count
  last young GC elapsed time
    last young GC eden before/after sizes in KBs size/committed(max)
    last young GC tenured before/after sizes in KBs size/committed(max)
  last old GC count
  last old GC elapsed time
    last old GC eden before/after sizes in KBs size/committed(max)
    last old GC tenured before/after sizes in KBs size/committed(max)
  total mutator secs                  total gc secs
  tenured live size KBs               tenured committed size KBs
  live hi water KBs (% of max)        live lo water KBs (% of max)
  live avge KBs (% of max)            live last 10 avge KBs (% of max)
  committed hi water KBs (% of max)   committed lo water KBs (% of max)
  committed avge KBs (% of max)       committed last 10 avge KBs (% of max)


This is a combined C++/Java application for use with OpenJDK and
Oracle's proprietary JVM. It relies on the Oracle JVMTI Tools API and
its current operation is specific to the GC setup of these two JVMs.
It will only report stats if you use the serial GC (-XX:+UseSerialGC)
or the default parallel GC (no flags needed).

Building
--------

To build the agent you will need make, gcc and mvn installed in your
runtime. In the top-level dir of the source tree execute:

  make dist

to build the deployable build products. They will be installed in a
subdir called target

  target/libballoon.so
  target/balloondriver-1.0.0.jar

To delete the target tree execute

  make clean

To build a version of the jar which includes a Test class which can be
used to churn over memory execute

  make clean
  make all

Using
-----

To use the agent pass the following arguments on the java command line

  $ java -agentpath:${BASEDIR}/target/libballoon.so \
         -cp ${BASEDIR}/target/balloon-1.0.0.jar \
         . . .

where BASEDIR identifies your git tree root. Alternatively, you can
add the target dir to the library load path and use the agentlib
argument

  $ LD_LIBRARY_PATH=${BASEDIR}/target
  $ java -agentlib:balloon \
         -cp ${BASEDIR}/target/balloon-1.0.0.jar \
         . . .

The agent will write memory management summary stats to file
.balloonstats.log in the current working directory.

Configuring
-----------

The agent accepts several options which can be appended to the shared
library name following an '=' separator.multiple options must be comma
separated. So, for example you can pass

  $ java -agentpath:${BASEDIR}/target/libballoon.so=sysout \
         -cp ${BASEDIR}/target/balloon-1.0.0.jar \
         . . .

or

  $ java -agentpath:${BASEDIR}/target/libballoon.so=verbose,all \
         -cp ${BASEDIR}/target/balloon-1.0.0.jar \
         . . .

Available options and their meanings are

  sysout -- write stats to the JVM's System.out
  verbose -- print messages detailing operation of the native agent
  all -- dump stats at every GC
  map -- does not yet do anything


Testing
-------

The source code includes a simple test class Test which can be used to
occupy and over memory at various sizings and frequencies. This
program creates objects at a fairly high rate, occasionally adding a
new object to its retained working set as a replacement for an
existing element of that retained set. Object sizes are varied over
time and have a profile skewed mostly towards small objects.  Just to
make things more interesting, the retained set size is allowed to
start growing to double its normal size with a probability of about 1
in 5000. During this growth phase it is eligible to be restored to the
normal size, with a probability of about 1 in 10000.

If you build with target all then Test.class will be located in the
ballon driver jar and the application can be executed as follows

java -agentpath:target/libballoon.so Test 4 1000

  The first argument (default 1, must be >= 1) is a (approximate)
multiplier for the retained working set size.

  The second argument (default 100, must be >= 0) specifies the number
of nanoseconds of work to be performed between calls to new.

Balloon?
--------

n.b. this repo contains prototype code for inflating and deflating
balloon objects to soak up heap, forcing any running Java apps to
operate in a smaller heap, or release it, providing more available
memory on demand. This functionality is not currently operational.
