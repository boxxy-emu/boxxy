/*
 * Xbox Live status for the lower screen: which account, and whether the
 * console is talking to Xbox Live (Insignia).  The guest tells the host
 * neither, so:
 *
 *  - Accounts are read from the console's hard disk (read_accounts).
 *  - Connected means Xbox Live is answering: replies on its secure UDP port
 *    3074 (presence, game traffic) or from a Kerberos server.  All of that
 *    is encrypted; only its arrival is used.
 *  - Which account signed in shows only when the sign-in itself crosses the
 *    network: Kerberos over UDP 88 names the account in clear (the AS-REQ
 *    asks for it, the AS-REP grants it, a KRB-ERROR refuses it).  The
 *    console caches its tickets on the disk, so a normal boot signs in
 *    without it (seen 2026-11-08: only port 3074 traffic); then the
 *    console's first account is shown.
 *
 * Fed by libslirp's UDP tap (patched, subprojects/packagefiles/slirp) on the
 * slirp thread; read by the app once a second.
 */
#include "qemu/osdep.h"
#include "qemu/main-loop.h"
#include "block/aio.h"
#include "system/block-backend.h"
#include <android/log.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "libslirp.h"
#include "xemu_android.h"

#define TAG "xemu-xboxlive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define KRB_PORT   88
#define XBL_PORT   3074      /* Xbox Live's secure UDP (game and presence) */
#define MAX_KDCS   8
/* Silence this long after the last reply from Xbox Live reads as offline. */
#define QUIET_MS   (90 * 1000)

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct {
    char user[64];              /* the signed-in (or last tried) account */
    bool user_ok;               /* its AS-REP arrived */
    int user_error;             /* its KRB-ERROR code, 0 if none */
    char pending[64];           /* account of the latest user AS-REQ */
    int64_t last_reply_ms;      /* last datagram from an Xbox Live server */
    uint32_t kdcs[MAX_KDCS];    /* addresses that answered Kerberos */
    int nkdcs;
} g;

static void schedule_account_read(void);

static int64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ---- just enough DER to find Kerberos's names ---------------------------- */

typedef struct {
    const uint8_t *p;
    size_t n;
} Der;

/* The next element of *d: its tag and contents. */
static bool der_next(Der *d, uint8_t *tag, Der *content)
{
    if (d->n < 2) {
        return false;
    }
    size_t i = 0;
    *tag = d->p[i++];
    size_t len = d->p[i++];
    if (len & 0x80) {
        unsigned k = len & 0x7f;
        if (k == 0 || k > 3 || i + k > d->n) {
            return false;
        }
        len = 0;
        while (k--) {
            len = len << 8 | d->p[i++];
        }
    }
    if (len > d->n - i) {
        return false;
    }
    content->p = d->p + i;
    content->n = len;
    d->p += i + len;
    d->n -= i + len;
    return true;
}

/* The contents of the single element in *d, if its tag is [want]. */
static bool der_unwrap(Der d, uint8_t want, Der *out)
{
    uint8_t tag;
    return der_next(&d, &tag, out) && tag == want;
}

/* Context field [k] of a SEQUENCE's contents. */
static bool der_field(Der seq, unsigned k, Der *out)
{
    uint8_t tag;
    Der c;
    while (der_next(&seq, &tag, &c)) {
        if (tag == (0xa0 | k)) {
            *out = c;
            return true;
        }
    }
    return false;
}

/* A [k] holding a GeneralString (realm), as text. */
static bool der_string_field(Der seq, unsigned k, char *buf, size_t n)
{
    Der f, s;
    if (!der_field(seq, k, &f) || !der_unwrap(f, 0x1b, &s) || s.n >= n) {
        return false;
    }
    memcpy(buf, s.p, s.n);
    buf[s.n] = '\0';
    return true;
}

/* A [k] holding a PrincipalName, as "a/b" text. */
static bool der_principal_field(Der seq, unsigned k, char *buf, size_t n)
{
    Der f, pn, names, list, s;
    uint8_t tag;
    size_t used = 0;

    if (!der_field(seq, k, &f) || !der_unwrap(f, 0x30, &pn) ||
        !der_field(pn, 1, &names) || !der_unwrap(names, 0x30, &list)) {
        return false;
    }
    buf[0] = '\0';
    while (der_next(&list, &tag, &s)) {
        if (tag != 0x1b || used + s.n + 2 > n) {
            return false;
        }
        if (used) {
            buf[used++] = '/';
        }
        memcpy(buf + used, s.p, s.n);
        used += s.n;
        buf[used] = '\0';
    }
    return used > 0;
}

static int der_int_field(Der seq, unsigned k)
{
    Der f, v;
    int r = 0;
    if (!der_field(seq, k, &f) || !der_unwrap(f, 0x02, &v) || v.n > 4) {
        return -1;
    }
    for (size_t i = 0; i < v.n; i++) {
        r = r << 8 | v.p[i];
    }
    return r;
}

/* ---- Kerberos -------------------------------------------------------------- */

/*
 * The console's own account (the machine, signed in first) as opposed to a
 * user's.  Insignia names machines after the console (realm MACS...), users
 * after their gamertag.
 */
static bool is_machine(const char *realm)
{
    return strstr(realm, "MACS") || strstr(realm, "macs");
}

static void on_kerberos(int outbound, uint32_t addr, const uint8_t *data,
                        size_t len)
{
    Der msg = { data, len }, body, seq, reqbody;
    uint8_t app;
    char cname[64] = "", realm[64] = "";

    if (!der_next(&msg, &app, &body) || !der_unwrap(body, 0x30, &seq)) {
        return;
    }
    pthread_mutex_lock(&g_lock);
    switch (app) {
    case 0x6a:      /* AS-REQ: [4] req-body { [1] cname, [2] realm } */
        if (outbound && der_field(seq, 4, &reqbody) &&
            der_unwrap(reqbody, 0x30, &reqbody)) {
            der_principal_field(reqbody, 1, cname, sizeof(cname));
            der_string_field(reqbody, 2, realm, sizeof(realm));
            LOGI("kerberos: sign-in request for %s@%s", cname, realm);
            if (cname[0] && !is_machine(realm)) {
                snprintf(g.pending, sizeof(g.pending), "%s", cname);
            }
        }
        break;
    case 0x6b:      /* AS-REP: [3] crealm, [4] cname */
        der_string_field(seq, 3, realm, sizeof(realm));
        der_principal_field(seq, 4, cname, sizeof(cname));
        LOGI("kerberos: signed in %s@%s", cname, realm);
        if (cname[0] && !is_machine(realm)) {
            snprintf(g.user, sizeof(g.user), "%s", cname);
            g.user_ok = true;
            g.user_error = 0;
            schedule_account_read();
        }
        break;
    case 0x7e: {    /* KRB-ERROR: [6] error-code, [7] crealm, [8] cname */
        int code = der_int_field(seq, 6);
        der_string_field(seq, 7, realm, sizeof(realm));
        der_principal_field(seq, 8, cname, sizeof(cname));
        LOGI("kerberos: error %d for %s@%s", code, cname, realm);
        /* 25 = pre-authentication required: the normal first answer. */
        if (code != 25 && g.pending[0] &&
            (!cname[0] || strcmp(cname, g.pending) == 0)) {
            snprintf(g.user, sizeof(g.user), "%s", g.pending);
            g.user_ok = false;
            g.user_error = code;
        }
        break;
    }
    default:        /* TGS exchanges: tickets for services, no names we need */
        break;
    }
    if (!outbound) {
        bool known = false;
        for (int i = 0; i < g.nkdcs; i++) {
            known |= g.kdcs[i] == addr;
        }
        if (!known && g.nkdcs < MAX_KDCS) {
            g.kdcs[g.nkdcs++] = addr;
        }
        g.last_reply_ms = now_ms();
    }
    pthread_mutex_unlock(&g_lock);
}

static void udp_tap(int outbound, uint32_t addr, uint16_t port,
                    const uint8_t *data, size_t len)
{
    if (port == KRB_PORT) {
        on_kerberos(outbound, addr, data, len);
        return;
    }
    if (outbound) {
        return;
    }
    /* Anything else Xbox Live answers keeps the connection "live". */
    pthread_mutex_lock(&g_lock);
    bool live = port == XBL_PORT;
    for (int i = 0; !live && i < g.nkdcs; i++) {
        live = g.kdcs[i] == addr;
    }
    if (live) {
        g.last_reply_ms = now_ms();
    }
    pthread_mutex_unlock(&g_lock);
}

/*
 * The accounts signed up on this console, read from its hard disk: a table
 * at sector 0xC of the config area (found on a 5960 disk, 2026-11-08), a
 * 12-byte header, then 0x6C-byte XONLINE_USER records: XUID, reserved, the
 * gamertag at +0x0C (16 bytes), flags, passcode, domain at +0x24, realm at
 * +0x38, then key material.  Only the gamertag is taken; the rest stays on
 * the disk.
 */
#define ACCOUNTS_OFFSET   0x1800
#define ACCOUNT_SIZE      0x6C
#define MAX_ACCOUNTS      8

static char g_accounts[MAX_ACCOUNTS][16];
static int g_naccounts = -1;          /* -1: not read yet */
static bool g_read_pending;

static bool printable(const uint8_t *p, size_t n)
{
    size_t i = 0;
    while (i < n && p[i]) {
        if (p[i] < 0x20 || p[i] > 0x7e) {
            return false;
        }
        i++;
    }
    return i > 0 && i < n;
}

/* On the main loop (the block layer's home); scheduled by the status poll. */
static void read_accounts(void *opaque)
{
    uint8_t buf[12 + MAX_ACCOUNTS * ACCOUNT_SIZE];
    BlockBackend *blk = blk_by_name("ide0-hd0");
    char names[MAX_ACCOUNTS][16];
    int n = 0;

    if (blk && blk_pread(blk, ACCOUNTS_OFFSET, sizeof(buf), buf, 0) >= 0) {
        for (int i = 0; i < MAX_ACCOUNTS; i++) {
            const uint8_t *rec = buf + 12 + i * ACCOUNT_SIZE;
            /* A record is a printable gamertag and a printable domain. */
            if (printable(rec + 0x0C, 16) && printable(rec + 0x24, 20)) {
                memcpy(names[n], rec + 0x0C, 16);
                names[n][15] = '\0';
                n++;
            }
        }
    }
    pthread_mutex_lock(&g_lock);
    if (n != g_naccounts) {
        LOGI("accounts on this console: %d%s%s", n, n ? ", first " : "",
             n ? names[0] : "");
    }
    memcpy(g_accounts, names, sizeof(names));
    g_naccounts = n;
    g_read_pending = false;
    pthread_mutex_unlock(&g_lock);
}

static void schedule_account_read(void)
{
    /* Caller holds g_lock. */
    if (!g_read_pending) {
        g_read_pending = true;
        aio_bh_schedule_oneshot(qemu_get_aio_context(), read_accounts, NULL);
    }
}

static int64_t g_start_ms;

void xemu_xboxlive_install(void)
{
    g_start_ms = now_ms();
    slirp_android_set_udp_tap(udp_tap);
}

/*
 * "state\taccount\taccounts": state is online (Xbox Live answering),
 * connecting (not heard from yet, early on), offline, or error:<kerberos
 * code>; account the gamertag signed in, as named by a sign-in seen this
 * session or else the console's first account; accounts how many the
 * console has.  Polled once a second.
 */
void xemu_xboxlive_status(char *buf, size_t n)
{
    static unsigned polls;
    pthread_mutex_lock(&g_lock);
    if (polls++ % 60 == 0) {
        schedule_account_read();
    }
    int64_t t = now_ms();
    const char *state;
    char err[24];
    if (g.user_error) {
        snprintf(err, sizeof(err), "error:%d", g.user_error);
        state = err;
    } else if (g.last_reply_ms && t - g.last_reply_ms < QUIET_MS) {
        state = "online";
    } else if (!g.last_reply_ms && t - g_start_ms < 60 * 1000) {
        state = "connecting";
    } else {
        state = "offline";
    }
    const char *who = g.user[0] ? g.user :
                      g.pending[0] ? g.pending :
                      g_naccounts > 0 ? g_accounts[0] : "";
    snprintf(buf, n, "%s\t%s\t%d", state, who,
             g_naccounts < 0 ? 0 : g_naccounts);
    pthread_mutex_unlock(&g_lock);
}
