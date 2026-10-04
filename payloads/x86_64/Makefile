# x86_64 PoC payloads for box64 validation — SoS-PS5-Android M1 (Task 1-a).
#
# Local (x86_64 host):      make static && ./hello_static
# CI cross (ARM64 runner):  make static CC=x86_64-linux-gnu-gcc
#
# CC: default gcc; overridable on the command line (command-line assignments
# take precedence over this file-level assignment).
# STATIC_LDFLAGS: defaults to -static for the `static` target. CI may override
# it to empty as a *visible* fallback if cross static linking is unavailable
# (e.g. cross crt files missing). Nothing is ever skipped silently.
#
# NOTE: `static`/`dynamic` are phony and ALWAYS rebuild their output. This is
# deliberate (payloads are tiny): a stale hello_static/hello_dyn must never
# mask a CC= or STATIC_LDFLAGS= override passed on the command line in CI.

CC             = gcc
CFLAGS         = -O1 -Wall -Wextra
STATIC_LDFLAGS ?= -static

.PHONY: all static dynamic clean

all: static dynamic

static:
	$(CC) $(CFLAGS) $(STATIC_LDFLAGS) -o hello_static hello_static.c

dynamic:
	$(CC) $(CFLAGS) -o hello_dyn hello_dyn.c

clean:
	rm -f hello_static hello_dyn
