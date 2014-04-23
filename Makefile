JAVA_INCLUDES=-I/usr/lib/jvm/java/include/linux -I/usr/lib/jvm/java/include

INCLUDES=${JAVA_INCLUDES} -I.

CXXFLAGS=-DASSERT -DDEBUG ${INCLUDES} -m64 -std=c++0x -DLinux -c -g -fPIC

CPPFLAGS=-DASSERT -DDEBUG ${INCLUDES} -m64 -DLinux -c -g -fPIC

LDFLAGS=-m64 -z noexecstack -shared -Wl,-soname,libballoon.so

SRCDIR=src/main/native

TARGETDIR=target

CC=gcc
CXX=g++
LD=g++

all: $(TARGETDIR) $(TARGETDIR)/libballoon.so

clean:
	rm -rf $(TARGETDIR)

$(TARGETDIR):
	mkdir $(TARGETDIR)

$(TARGETDIR)/libballoon.so: $(TARGETDIR)/balloonagent.o $(TARGETDIR)/balloonutil.o
	$(LD) $(LDFLAGS) -o $@ $?


$(TARGETDIR)/%.o: $(SRCDIR)/%.cpp
	$(CXX) $(CXXFLAGS) -o $@ $<

$(TARGETDIR)/%.o: $(SRCDIR)/%.c
	$(CC) $(CPPFLAGS) -o $@ $<

