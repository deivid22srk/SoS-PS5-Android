/*
 * SoS-PS5-Android — M1 PoC payload (STATIC x86-64 ELF).
 *
 * MERGED version (tasks 1-a + 1-b) — DO NOT REMOVE the markers
 * `SOS_POC_STATIC_OK` or `SOS_POC_STATIC_MATH_OK` (asserted by CI
 * .github/workflows/build.yml and by the Android activity).
 *
 *   - Task 1-a (native box64-arm64 CI): deterministic math self-check —
 *     harmonic sum H_1000 in double (SSE2) + uint64 fold, compared against
 *     constants hardcoded below; proves faithful translation of 64-bit
 *     integer and scalar double arithmetic.
 *   - Task 1-b (APK PoC): argv/pid/uid/uname diagnostics shown on screen;
 *     the activity scans stdout for SOS_POC_STATIC_OK.
 *
 * Cross-compiled on CI with: x86_64-linux-gnu-gcc -static (see
 * payloads/x86_64/Makefile and scripts/ci-build-box64.sh). Packaged in the
 * APK as jniLibs/arm64-v8a/libpayload64.so. ASCII-only output on purpose.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/utsname.h>

#define MATH_ACC_EXPECTED 7.4854708605503433              /* H_1000, sequential double sum */
#define MATH_ICHK_EXPECTED 4201695289734782276ULL         /* fold64(H_1000 bits, 1000) */
#define MATH_TOLERANCE 1e-9

/* Deterministic fold over the IEEE-754 bit pattern of the accumulator
 * (FNV-1a mix + murmur3 finalizer). No libm, no intrinsics. */
static uint64_t fold64(double acc, int n)
{
    uint64_t bits, h;
    memcpy(&bits, &acc, sizeof(bits));
    h = 1469598103934665603ULL;            /* FNV-1a offset basis */
    h = (h ^ (uint64_t)n) * 1099511628211ULL;
    h = (h ^ bits) * 1099511628211ULL;
    h ^= h >> 33;
    h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33;
    return h;
}

static int math_selfcheck(void)
{
    volatile double acc = 0.0;             /* volatile: force runtime SSE2 ops */
    int i;
    for (i = 1; i <= 1000; i++)
        acc += 1.0 / (double)i;

    {
        double a = acc;
        double diff = a - MATH_ACC_EXPECTED;
        if (diff < 0) diff = -diff;
        uint64_t ichk = fold64(a, 1000);
        printf("math: acc=%f expected=%f diff=%.3e ichk=%llu expected=%llu\n",
               a, (double)MATH_ACC_EXPECTED, diff,
               (unsigned long long)ichk, (unsigned long long)MATH_ICHK_EXPECTED);
        return (diff < MATH_TOLERANCE) && (ichk == MATH_ICHK_EXPECTED);
    }
}

int main(int argc, char **argv)
{
    struct utsname u;

    printf("SOS-PS5 PoC: static x86-64 payload started\n");
    printf("argc=%d argv0=%s pid=%d uid=%d\n",
           argc, (argc > 0 && argv[0]) ? argv[0] : "?", (int)getpid(), (int)getuid());

    memset(&u, 0, sizeof(u));
    if (uname(&u) == 0) {
        printf("uname: sys=%s nodename=%s release=%s machine=%s\n",
               u.sysname, u.nodename, u.release, u.machine);
    } else {
        printf("uname failed\n");
    }

    if (math_selfcheck()) {
        printf("SOS_POC_STATIC_MATH_OK\n");
    } else {
        printf("SOS_POC_STATIC_MATH_FAIL\n");
        return 1;
    }

    fflush(stdout);
    printf("SOS_POC_STATIC_OK\n");
    fflush(stdout);
    return 0;
}
