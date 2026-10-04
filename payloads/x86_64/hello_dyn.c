/*
 * hello_dyn.c — M1 proof-of-concept payload, dynamically linked (Task 1-a).
 *
 * Purpose: validate box64's dynamic-linking path on an ARM64 host: the guest
 * ELF has a PT_INTERP (ld-linux-x86-64.so.2) and links against the x86-64
 * glibc (cross glibc installed in CI via libc6-amd64-cross). box64 must load
 * the guest interpreter/libraries and resolve printf from them.
 *
 * The CI job locates the cross ld.so/libc.so.6, points box64 at them via
 * BOX64_LD_LIBRARY_PATH and asserts this marker on stdout.
 * Dynamic execution under box64 is an M1 stretch goal: CI reports
 * DYNAMIC_TEST_FAILED in the summary without hard-failing the workflow.
 */

#include <stdio.h>
#include <stdlib.h>

int main(void)
{
    printf("SOS_POC_DYNAMIC_OK\n");
    exit(0);
}
