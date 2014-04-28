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

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <sys/mman.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <fcntl.h>
#include <queue>
#include <deque>
#include "balloonutil.h"
#include "balloonagent.hpp"

/*
 * balloon monitoring agent
 *
 * an native agnet which manages a Java therad that monitors GC
 * activity and writes GC stats to ${CWD}/.balloonstats.log
 *
 * run using java command line argument
 *
 *    -agentpath:/path/to/libballoon.so[=args]
 *
 * or add lib dir to LD_LIBRARY_PATH and use
 *
 *    -agentlib:balloon[=args]
 *
 * optional args follow the = sign, are comma separated and include
 *  verbose -- write agent trace messages to stdout
 *  sysout -- write balloon stats to System.out
 *  map -- enable not yet fully implemented function
 */

// flag which enables or disables memory remapping
static int do_balloon_mapping = 0;

// flag which redirects output to System.out if set otherwise leaves it
// going to the default log file (${CWD}/.balloonstats.log)
static jvalue use_sysout = { 0 };

// flag which requests dumping stats at every GC if set to true otherwise
// stats are dumped at old gc so long as a minimum of DUMP_INTERVAL_MIN
// seconds has passed since the last old gc dumps or at young GC if there
// has not been a prior (old or young gc) dump during the last
// DUMP_INTERVAL_MAX seconds

static jvalue dump_all = { 0 };

// lock used to sequence concurrent actions performed
// by JVMTI callbacks and the agent manager jthread
static jrawMonitorID agent_lock;

// types of events notified to the agent manager jthread
// from registered JVMTI callbacks
typedef enum {
  Init,
  End,
  Terminate
} GCEvent;

// queue used to communicate events from JVMTI
// callbacks to the agent jthread
static std::queue<GCEvent> gcNotifyQueue;

// flag indicating that init failed and we should stop monitoring
static long failed = 0;

// flag allowing agent jthread to detect that a GC occurred
// while it is performing balloon management
static jboolean interrupted = JNI_FALSE;

// flag allowing agent jthread to detect that the VM has died
static jboolean vmdead = JNI_FALSE;

// details for an individual balloon registered by the agent thread
typedef struct {

  jbyteArray globalRef;
  void *rawRef;
  int offset;
  long size;
} BalloonType;

// deque listing all registered balloons
// used as a stack
static std::deque<BalloonType> balloons;

// JVMTI state used by registered callbacks
static JNIEnv *jniEnv = NULL;
static JavaVM *jvm = NULL;
static jvmtiEnv *agentJvmti = NULL;

// file descriptor for a file holding a single
// all-zeros mapped file page
static int zero_fd = -1;

// the page size for this host
static long page_size = 0;

/*
 * Details of class com.redhat.openjdk.balloon.MemoryManager
 */
static struct MemoryManager
{
  jclass MemoryManagerClass;
  jmethodID init;
  jmethodID gcEnd;
  jmethodID terminate;
} theMemoryManager;


/*
 * Enter agent monitor protected section
 */
static void
enterAgentMonitor(jvmtiEnv *jvmti)
{
  jvmtiError err;
  err = jvmti->RawMonitorEnter(agent_lock);
  check_jvmti_error(jvmti, err, "raw monitor enter");
}

/*
 * wait on agent monitor notify
 *
 * should not be called withotu first calling
 * enterAgentMonitor to obtain the agent lock
 */
static void
waitAgentMonitor(jvmtiEnv *jvmti)
{
  jvmtiError err;
  err = jvmti->RawMonitorWait(agent_lock, 0);
  check_jvmti_error(jvmti, err, "raw monitor wait");
}

/*
 * notify agent monitor
 *
 * should not be called withotu first calling
 * enterAgentMonitor to obtain the agent lock
 */
static void
notifyAgentMonitor(jvmtiEnv *jvmti)
{
  jvmtiError err;
  err = jvmti->RawMonitorNotify(agent_lock);
  check_jvmti_error(jvmti, err, "raw monitor notify");
}

/*
 * Exit agent monitor protected section
 */
static void
exitAgentMonitor(jvmtiEnv *jvmti)
{
  jvmtiError err;
  err = jvmti->RawMonitorExit(agent_lock);
  check_jvmti_error(jvmti, err, "raw monitor exit");
}

/*
 * utility to create a new jthread
 */
static jthread
alloc_thread(JNIEnv *env)
{
  jclass    thrClass;
  jmethodID cid;
  jthread   res;

  thrClass = env->FindClass("java/lang/Thread");
  if ( thrClass == NULL ) {
    printf("Cannot find Thread class\n");
  }
  cid = env->GetMethodID(thrClass, "<init>", "()V");
  if ( cid == NULL ) {
    printf("Cannot find Thread constructor method\n");
  }
  res = env->NewObject(thrClass, cid);
  if ( res == NULL ) {
    printf("Cannot create new Thread object\n");
  }
  return res;
}

/*
 * agent thread that calls into Java in response to
 * JVMTI notifications
 */
static void JNICALL
agentThread(jvmtiEnv* jvmti, JNIEnv* jni, void *p)
{
  stdout_message("GC worker started...\n");
  int inited = 0;
  int skipped = 0;

  for (;;) {
    //printf("agentThread waiting for GCEvent...\n");
    enterAgentMonitor(jvmti);
    waitAgentMonitor(jvmti);
    GCEvent event = gcNotifyQueue.front();
    gcNotifyQueue.pop();
    // skip repeated End events and ditch for an Init or Terminate
    while (event == GCEvent::End && !gcNotifyQueue.empty()) {
      skipped += inited; // only count misses after init has happened
      GCEvent event = gcNotifyQueue.front();
      stdout_message("GC worker pop...\n");
      gcNotifyQueue.pop();
    }
    exitAgentMonitor(jvmti);
    if(event == GCEvent::Init) {
      stdout_message("Calling MemoryManager.init\n");
      inited = jni->CallStaticBooleanMethod(theMemoryManager.MemoryManagerClass, theMemoryManager.init, use_sysout, dump_all);
      failed = !inited;
    } else if (event == GCEvent::End  && inited) {
      interrupted = JNI_FALSE;
      stdout_message("Calling MemoryManager.gcEnd()\n");
      jni->CallStaticVoidMethod(theMemoryManager.MemoryManagerClass, theMemoryManager.gcEnd);
    } else if (event == GCEvent::Terminate  && inited) {
      interrupted = JNI_FALSE;
      stdout_message("Skipped %d end events\n", skipped);
      stdout_message("Calling MemoryManager.terminate()\n");
      jni->CallStaticVoidMethod(theMemoryManager.MemoryManagerClass, theMemoryManager.terminate);
    }

    /* Perform arbitrary JVMTI/JNI work here to do post-GC cleanup */
    //printf("post-GarbageCollectionFinish actions...\n");
  }
}

/*
 * remap a balloon's array data area to a zero file backing
 */
static void unmap_balloon(BalloonType balloon)
{
  long paddr = (long)(balloon.rawRef) + balloon.offset;
  long rem = paddr % page_size;
  int len = balloon.size - balloon.offset;
  if (rem) {
    paddr += (page_size - rem);
    len += (int)(rem - page_size);
  }
  len = len / page_size;
  char *paddr2 =  (char *)paddr;
  int prot = PROT_READ|PROT_WRITE;
  int flags = MAP_SHARED|MAP_FIXED;
  stdout_message("start zero file map at %p\n", paddr2);
  for (int i = 0; i < len; i++) {
    int ok = munmap(paddr2, page_size);
    if (ok != 0) {
      perror("munmap");
      fatal_error("failed to zero unmap %p 0x%x (0x%x) errno=%d\n", paddr2, page_size, i, errno);
    }
    char *res = (char *)mmap(paddr2, page_size, prot, flags, zero_fd, 0);
    if (res != paddr2) {
      if (res == MAP_FAILED) {
        perror("mmap");
        fatal_error("failed to zero map %p 0x%x (0x%x) errno=%d\n", paddr2, page_size, i, errno);
      } else {
        fatal_error("bad zero map %p ==> %p\n", paddr2, res);
      }
    }
    // stdout_message("zero file mapped [%p, %p)\n", paddr2, paddr2 + page_size);
    paddr2 += page_size;
  }
  stdout_message("end zero file map at %p\n", paddr2);
  stdout_message("zero mapped %x pages\n", len);
}

/*
 * remap a balloons array data area to anonymous heap data
 */
static void map_balloon(BalloonType balloon)
{
  long paddr = (long)(balloon.rawRef) + balloon.offset;
  long page_size = sysconf(_SC_PAGESIZE);
  long rem = paddr % page_size;
  int len = balloon.size - balloon.offset;
  if (rem) {
    paddr += (page_size - rem);
    len += (int)(rem - page_size);
  }
  len = len / page_size;
  char *paddr2 = (char *)paddr;
  int prot = PROT_READ|PROT_WRITE;
  int flags = MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED;
  stdout_message("start anon file map at %p\n", paddr2);
  for (int i = 0; i < len; i++) {
    int ok = munmap(paddr2, page_size);
    if (ok != 0) {
      perror("munmap");
      fatal_error("failed to zero unmap %p 0x%x (0x%x) errno=%d\n", paddr2, page_size, i, errno);
    }
    char *res = (char *)mmap(paddr2, page_size, prot, flags, -1, 0);
    if (res != paddr2) {
      if (res == MAP_FAILED) {
        perror("mmap");
        fatal_error("failed to anon map %p 0x%x (0x%x) errno=%d\n", paddr2, page_size, i, errno);
      } else {
        fatal_error("bad anon map %p ==> %p\n", paddr2, res);
      }
    }
    // stdout_message("anon mapped [%p, %p)\n", paddr2, paddr2 + page_size);
    paddr2 += page_size;
  }
  stdout_message("end anon file map at %p\n", paddr2);
  stdout_message("anon mapped %x pages \n", len);
}

/*
 * native method implementation for BalloonManager to
 * register a balloon
 *
 * returns true if a GC occurred during registration in
 * which case the balloon is not registered
 */

JNIEXPORT jboolean JNICALL
Java_com_redhat_openjdk_balloon_BalloonManager_registerBalloon(JNIEnv *env, jclass unused, jbyteArray array)
{
  BalloonType balloon;
  balloon.globalRef = (jbyteArray)env->NewGlobalRef(array);
  agentJvmti->GetObjectSize(balloon.globalRef, &balloon.size);
  // fetch the raw ref and compute the offset while we have the critical lock
  void *rawarray = env->GetPrimitiveArrayCritical(balloon.globalRef, 0);
  balloon.rawRef = *(void**)balloon.globalRef;
  balloon.offset = ((char *)rawarray) - ((char *)balloon.rawRef);
  env->ReleasePrimitiveArrayCritical(balloon.globalRef, rawarray, 0);
  //
  enterAgentMonitor(agentJvmti);
  jboolean result = interrupted;
  if (!interrupted) {
    if (do_balloon_mapping) {
      // refetch the raw ref now we have the agent lock
      // it might have changed after we released the critical lock
      balloon.rawRef = *(void**)balloon.globalRef;
    }
    balloons.push_front(balloon);
    if (do_balloon_mapping) {
      unmap_balloon(balloon);
    }
  }
  exitAgentMonitor(agentJvmti);

  stdout_message("Allocated(%p,%p)=%ld%s\n", balloon.globalRef, balloon.rawRef, balloon.size, (result ? " interrupted!" : " no gc"));
  return result;
}

/*
 * native method implementation for BalloonManager to
 * unregister a balloon
 *
 * returns true if a GC occurred during registration in
 * which case the balloon is not unregistered
 */

JNIEXPORT jboolean JNICALL
Java_com_redhat_openjdk_balloon_BalloonManager_unregisterBalloon(JNIEnv *env, jclass unused, jbyteArray array);
jboolean unregister_balloon(JNIEnv *env, jbyteArray array)
{
  // should always be true
  if(balloons.size() > 0) {
    // pop the front balloon
    enterAgentMonitor(agentJvmti);
    jboolean result = interrupted;
    BalloonType balloon = balloons.front();
    if (!interrupted) {
      balloons.pop_front();
      if (do_balloon_mapping) {
        map_balloon(balloon);
      }
    }
    exitAgentMonitor(agentJvmti);
    jlong bsize = 0;
    agentJvmti->GetObjectSize(balloon.globalRef, &bsize);
    stdout_message("Popped balloon(%p,%p) = %ld\n", balloon.globalRef, balloon.rawRef, bsize);
    env->DeleteGlobalRef(balloon.globalRef);
    return result;
  }
}

/*
 * JVMTI callback for VMInit event
 */
static void JNICALL vmInit(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  stdout_message("vmInit\n");

  // jvmti->SetVerboseFlag(jvmtiVerboseFlag::JVMTI_VERBOSE_JNI, 1);
  // jvmti->SetVerboseFlag(jvmtiVerboseFlag::JVMTI_VERBOSE_GC, 1);

  // set up page size and create zero page to map against

  page_size = sysconf(_SC_PAGESIZE);

  char name[200];
  sprintf(name, "/tmp/balloon%x", getpid());
  zero_fd = open(name, O_RDWR|O_CREAT|O_EXCL, S_IRUSR|S_IWUSR);
  if (zero_fd < 0) {
    perror("open");
    fatal_error("failed to open zero map file %s\n", name);
  } else {
    unlink(name);
    char b = '\0';
    for (int i = 0; i < page_size; i++) {
      int res = write(zero_fd, &b, 1);
      if (res < 0) {
        perror("write");
        fatal_error("failed to write to zero map file %s\n", name);
      } else if (res == 0) {
        i--;
      }
    }
  }

  stdout_message("Created, unlinked and filled backing page file: %s\n", name);

  // Load the MemoryManager class
  memset(&theMemoryManager, 0, sizeof(theMemoryManager));
  theMemoryManager.MemoryManagerClass = jni->FindClass("com/redhat/openjdk/balloon/MemoryManager");
  if(theMemoryManager.MemoryManagerClass == NULL) {
    printf("Failed to load com/redhat/openjdk/balloon/MemoryManager, exiting...\n");
    JavaVM *jvm = NULL;
    jni->GetJavaVM(&jvm);
    jvm->DestroyJavaVM();
  }
  stdout_message("Loaded MemoryManagerClass: %p\n", theMemoryManager.MemoryManagerClass);
  theMemoryManager.init = jni->GetStaticMethodID(theMemoryManager.MemoryManagerClass, "init", "(ZZ)Z");
  stdout_message("Loaded init: %p\n", theMemoryManager.init);
  theMemoryManager.gcEnd = jni->GetStaticMethodID(theMemoryManager.MemoryManagerClass, "gcEnd", "()V");
  stdout_message("Loaded gcEnd: %p\n", theMemoryManager.gcEnd);
  theMemoryManager.terminate = jni->GetStaticMethodID(theMemoryManager.MemoryManagerClass, "terminate", "()V");
  stdout_message("Loaded terminate: %p\n", theMemoryManager.terminate);
  jvmtiError err = jvmti->RunAgentThread(alloc_thread(jni), &agentThread, NULL, JVMTI_THREAD_MAX_PRIORITY);

  // enable the gc callbacks
  //jvmti->SetEventNotificationMode(JVMTI_ENABLE,
  //                                JVMTI_EVENT_GARBAGE_COLLECTION_START, (jthread)NULL);
  jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                  JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, (jthread)NULL);

  enterAgentMonitor(jvmti);
  gcNotifyQueue.push(GCEvent::Init);
  notifyAgentMonitor(jvmti);
  exitAgentMonitor(jvmti);
  stdout_message("Notified GC monitor thread : init\n");

  stdout_message("vmInit done\n");
}

/*
 * JVMTI callback for VMStart event
 */
static void JNICALL vmStart(jvmtiEnv *jvmti, JNIEnv* jni) {
  stdout_message("vmStart\n");
  jniEnv = jni;
  agentJvmti = jvmti;
  stdout_message("vmStart done\n");
}

/*
 * JVMTI callback for ResourceExhausted event
 */
static void JNICALL
ResourceExhausted(jvmtiEnv *jvmti_env,
                  JNIEnv* jni_env,
                  jint flags,
                  const void* reserved,
                  const char* description) {
  stdout_message("ResourceExhausted(%d), %s\n", flags, description);
}

/*
 * JVMTI callback for vmDeath event
 */
static void JNICALL
vmDeath(jvmtiEnv *jvmti, JNIEnv *env)
{
  jvmtiError          err;
  stdout_message("vmDeath\n");
  /* Make sure everything has been garbage collected */
  err = jvmti->ForceGarbageCollection();
  check_jvmti_error(jvmti, err, "force garbage collection");

  /* Disable events and dump the heap information */
  enterAgentMonitor(jvmti); {
  gcNotifyQueue.push(GCEvent::Terminate);
  notifyAgentMonitor(jvmti);
  } exitAgentMonitor(jvmti);
  stdout_message("vmDeath done\n");
}

/*
 * JVMTI callback for endGC event
 */
static void JNICALL
endGC(jvmtiEnv *jvmti) {
  stdout_message("agent::endGC\n");
  enterAgentMonitor(jvmti);
  if (!failed) {
    // interrupt any in flight allocation or deallocation
    interrupted=JNI_TRUE;
    // fix up any raw references
    int i = 0;
    for (std::deque<BalloonType>::iterator it = balloons.begin(); it != balloons.end(); it++) {
      BalloonType &balloon = *it;
      jbyteArray globalRef = balloon.globalRef;
      void *oldRawRef = balloon.rawRef;
      void *rawRef = *(void **)globalRef;
      if (oldRawRef != rawRef) {
        stdout_message("balloons[%d] moved from 0x%lx to 0x%lx\n", i, oldRawRef, rawRef);
        if (do_balloon_mapping) {
          map_balloon(balloon);
        }
        balloon.rawRef = rawRef;
        if (do_balloon_mapping) {
          unmap_balloon(balloon);
        }
      }
      i++;
    }
    gcNotifyQueue.push(GCEvent::End);
    notifyAgentMonitor(jvmti);
    exitAgentMonitor(jvmti);
    stdout_message("Notified GC monitor thread : end\n");
  } else {
    exitAgentMonitor(jvmti);
  }
  stdout_message("agent::endGC done\n");  
}


/*
 * utility function to parse arguments provided with the the agentpath/lib java option
 */
void processAgentOptions(char *options)
{
  char *curr = options;
  while (curr) {
    // successive options are separated by ","
    char *next = strchr(curr, ',');
    int len;
    if (next) {
      len = next - curr;
    } else {
      len = strlen(curr);
    }

    if (strncmp(curr, "map", len) == 0) {
      do_balloon_mapping = 1;
    } else if (strncmp(curr, "verbose", len) == 0) {
      set_verbose();
    } else if (strncmp(curr, "sysout", len) == 0) {
      use_sysout.z = 1;
    } else if (strncmp(curr, "all", len) == 0) {
      dump_all.z = 1;
    } else {
      printf("unknown agent option <%*s>\n", len, curr);
    }
    curr = (next ? next + 1 : next);
  }
}

/**
 * JVMTI Agent_OnLoad entry point
 *
 * Run with JVM option:
 *    -agentlib:balloon=<options>
 * or
 *    -agentpath:<path-to-agent/libballoon.so>=<options>
 * @param vm
 * @param options
 * @param reserved
 * @return
 */
JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
  jvmtiEnv              *jvmti;
  jvmtiError             error;
  jint                   res;
  jvmtiCapabilities      capabilities;
  jvmtiEventCallbacks    callbacks;

  processAgentOptions(options);

  jvm = vm;
  stdout_message("Agent_OnLoad(jvm=%p)\n", jvm);
  vm->GetEnv((void **)&jvmti, JVMTI_VERSION);
  memset(&capabilities,0, sizeof(capabilities));
  capabilities.can_generate_all_class_hook_events  = 1;
  capabilities.can_tag_objects                     = 1;
  capabilities.can_get_source_file_name            = 1;
  capabilities.can_get_line_numbers                = 1;
  capabilities.can_generate_garbage_collection_events = 1;
  capabilities.can_tag_objects = 1;
  capabilities.can_generate_resource_exhaustion_heap_events = 1;
  error = jvmti->AddCapabilities(&capabilities);

  (void)memset(&callbacks,0, sizeof(callbacks));
  callbacks.VMStart           = &vmStart;
  callbacks.VMInit            = &vmInit;
  callbacks.GarbageCollectionFinish = &endGC;
  callbacks.ResourceExhausted = &ResourceExhausted;

  error = jvmti->SetEventCallbacks(&callbacks, (jint)sizeof(callbacks));

  error = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                          JVMTI_EVENT_VM_START, (jthread)NULL);
  error = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                          JVMTI_EVENT_VM_INIT, (jthread)NULL);
  error = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                          JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, (jthread)NULL);
  //error = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
  //        JVMTI_EVENT_GARBAGE_COLLECTION_START, (jthread)NULL);
  //error = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
  //        JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, (jthread)NULL);

  error = jvmti->CreateRawMonitor("agent data", &(agent_lock));


  if(options != NULL) {

  }
  /* Add jar file to boot classpath */
  //const char* jar = "";
  //error = jvmti->AddToBootstrapClassLoaderSearch(jar);
  //check_jvmti_error(jvmti, error, "Cannot add to boot classpath");

  stdout_message("Agent_OnLoad done\n");
  return JNI_OK;
}

/**
 * JVMTI Agent_OnUnload entry point
 */
JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM *vm) {
  stdout_message("Agent_OnUnload(jvm=%p, agentJvmti=%p)\n", jvm, agentJvmti);
  enterAgentMonitor(agentJvmti);
  gcNotifyQueue.push(GCEvent::Terminate);
  notifyAgentMonitor(agentJvmti);
  exitAgentMonitor(agentJvmti);
  stdout_message("Agent_OnUnload done\n");
}
