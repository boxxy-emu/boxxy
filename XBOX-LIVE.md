# Xbox Live (Insignia) in Boxxy

The working record for online play: how the original Xbox reaches Xbox Live,
what Insignia (the revival, insignia.live) needs, what Boxxy automates, and
what is still open. Started 2026-11-08 (OPEN-WORK A11). Keep it current, and
record dead ends here too.

## How it fits together

```
Xbox (guest, 10.0.2.15)
  │ DHCP, DNS (to 10.0.2.3 or any server), Kerberos, game traffic
  ▼
libslirp NAT inside the emulator (ui/xemu-net.c)
  │  UDP DNS ──► InsigniaDns on 127.0.0.1:<port> (Kotlin, emulator process)
  │                 Xbox Live names ─► Insignia's servers (via normal DNS)
  │                 everything else ─► Android's resolver
  │  everything else ──► this device's sockets ──► the internet
  ▼
Insignia: macs/as/tgs.insig.uk (49.13.57.101 on 2026-11-08), xexds, insignia.live
```

The NAT is xemu's own (`g_config.net`, backend NAT). On Android it is switched
on from the app before the machine is built (`NativeInterface.setNetwork`),
because xemu.toml is rewritten on every launch.

## What Insignia needs, and who does it

| Need | Source | Boxxy |
|---|---|---|
| Network connection | — | **Automatic.** NAT on for Xbox Live setup launches; Settings > Network for games. |
| Insignia's DNS for Xbox Live names | insignia.live/connect: primary 46.101.64.175 | **Automatic, built in** (InsigniaDns, below). The Xbox can stay on automatic DNS. |
| Microsoft dashboard 5960 (`D:1.00.5960.01`) | insignia.live/connect | **User supplies a zip; Boxxy installs it** (DashboardInstaller). Can't be shipped: Microsoft's. |
| Non-zero HDD key | Insignia guide, "common faults" | **Already true.** xemu generates a random one with the EEPROM (`hw/xbox/eeprom_generation.c:103`). |
| Console registered (Setup Assistant, "Register Xbox") | Insignia guide, "prepare your console" | **Boxxy downloads and boots it**; the user presses Register. |
| Subscription code | insignia.live: register an email, code arrives by email (staged rollout) | **User.** Setup opens the site. |
| Account (gamertag) made in the dashboard | Insignia guide, "sign-up" | **User**, in the 5960 dashboard; Setup shows the steps. |

Sign-up in the dashboard (Insignia guide): country → terms → gamertag → the
subscription code → test card `4111 1111 1111 1111` (never a real card) and a
phone number in a valid format → **the same email** used at insignia.live →
PIN. Codes never use `0 1 E I L N O S U Z`.

**Identity.** Insignia knows the console by the EEPROM (serial, MAC, HDD key),
which Boxxy keeps at `filesDir/eeprom.bin`. Clearing app data or reinstalling
makes a new one, and the console must register again. Not yet handled: a way
to back up and restore it (open item below).

## The setup screen (Settings > Network > Set up Xbox Live)

`settings/XboxLiveSetupScreen.kt`. Five steps; the first unfinished one is
outlined. Opening it turns networking on with Insignia DNS.

1. **Subscription code** — opens insignia.live; "I have my code".
2. **Connection** — done for you.
3. **Install the Xbox dashboard** — choose a zip → checked → installed (below).
   "It's already installed" for users who have it.
4. **Register this Xbox** — downloads `Insignia-Setup.iso` (latest release of
   github.com/insignia-live/setup-assistant-release, 1.5 MB, verified as an
   XISO) to `filesDir/xboxlive/` and boots it. "It's registered".
5. **Create your account** — opens the dashboard; sign-up steps beside it.

Results that another process writes are files in `filesDir/xboxlive/`
(SharedPreferences are per-process caches): `dashboard_installed`,
`dashboard_install_error`, `xboxdash_previous.xbe`, `Insignia-Setup.iso`.
The screen re-reads them on resume. User ticks are `main_prefs`
(`xboxlive_have_code`, `xboxlive_have_dashboard`, `xboxlive_registered`).

Xbox Live setup launches (`EXTRA_SYSTEM_LAUNCH`) are always online and never
save Quick Resume; no-disc dashboard launches never do either. A resume point
of a half-installed disk, or of the dashboard, has no use.

## Dashboard installer

`xboxlive/DashboardInstaller.kt`, run by `EmulationActivity` for
`EXTRA_INSTALL_DASHBOARD`.

The HDD is a qcow2 image holding a FATX disk. Rather than write qcow2 and
FATX from the app, the files go in through the guest: Boxxy boots the dashboard
already on the disk — xemu's prebuilt HDD image ships one that runs an FTP
server ("CMS FTP Server", user/pass `xbox`) — and uploads to it. The guest's
own file system does the writing.

- **Port forward, loopback only.** `NativeInterface.setNetForward(port, 21)`
  forwards `127.0.0.1:<random port>` to the guest's port 21
  (`g_android_net_fwd_loopback` makes `ui/xemu-net.c` bind loopback instead of
  INADDR_ANY), so nothing else on the network can reach the disk.
- **Active FTP.** The client sends `PORT 10,0,2,2,…`: the guest connects to the
  NAT's host address, which slirp maps to this device's loopback, where the
  client listens. Passive mode would need a second forward for a port the
  server only picks per transfer.
- **Server quirks found by probing (2026-11-08):** drives are `/C /D /E /X /Y
  /Z`; `LIST` ignores its path argument (CWD first); `FEAT`'s multi-line reply
  is malformed (don't send it); `MKD` of an existing folder answers 550;
  `STOR` overwrites, including the running `xboxdash.xbe`; names with spaces
  work; `SIZE` works; `RNFR/RNTO` work.
- **Checks.** Before launching: the zip has `xboxdash.xbe` (the shallowest one
  marks the root), `xodash/xonlinedash.xbe` (Xbox Live; refused without it) and
  `xboxdashdata.185ead00` (5960; warn and ask otherwise — the folder is named
  after xboxdash.xbe's certificate version, 0x185ead00 in 5960). After each
  file: `SIZE` must equal the bytes sent.
- **Keeps the old dashboard** as `xboxdash_previous.xbe` (first install only).
- **Result, 2026-11-08, a 5960 zip:** 137 files (145 MB) in ~30 s, all
  sizes verified; the dashboard boots to its first-run notice and main menu.

**Needs xemu's dashboard on the disk.** A disk without an FTP server (another
dashboard, or 5960 already) times out after 120 s with an explanation, so
"Install again" over 5960 fails until xemu's dashboard is put back.

## Built-in Insignia DNS

`xboxlive/InsigniaDns.kt` plus a libslirp patch.

**Why.** On the test network (2026-11-08) Insignia's DNS
server 46.101.64.175 does not answer at all — no DNS over UDP or TCP, no ping —
while 8.8.8.8 answers and Insignia's Kerberos server 49.13.57.101 pings fine.
insignia.live/connect describes exactly this ("DNS hijacking detected… your
ISP is re-routing DNS requests") and points to insigniaDNS.

**What insigniaDNS is** (MIT, github.com/insignia-live/insigniaDNS, ~200 lines
of Python): it downloads `https://insignia.live/dns_zones.json` and answers
those names. On 2026-11-08 the table is five "proxy" entries — answer with the
A record of another name, looked up through normal DNS:

| Xbox asks | Answered with | Resolved to (2026-11-08) |
|---|---|---|
| macs.xboxlive.com | macs.insig.uk | 49.13.57.101 |
| as.xboxlive.com | as.insig.uk | 49.13.57.101 |
| tgs.xboxlive.com | tgs.insig.uk | 49.13.57.101 |
| xds.xboxlive.com | xexds.xboxlive.com | 20.83.102.213 |
| insignia.live | insignia.live | 104.25.46.9 / .47.9 |

**Boxxy's version.** The same table (fetched at start; a built-in copy if that
fails), answered by a UDP DNS server on `127.0.0.1:<port>` in the emulator
process. Other A queries go to Android's resolver (private DNS and VPNs keep
working); other record types get an empty answer. `SLIRP_DNS_REDIRECT=
127.0.0.1:<port>` (set by `NativeInterface.setDnsRedirect`) makes slirp send
**every** UDP query to port 53 there, whichever server the guest addressed —
so it also works when the Xbox's own settings (or the Setup Assistant) name
46.101.64.175. Patch: `sotranslate_out4` in `src/socket.c`, kept with the
SLIRP_DNS change in `subprojects/packagefiles/slirp/android-dns.patch`.

Replies are rewritten to come from the server and port the guest asked
(`sotranslate_in`, same patch). Without that they arrived from
`10.0.2.3:<resolver port>`: the MS dashboard took them, but lwIP (nxdk, so
the Setup Assistant) dropped them and retried, and Troubleshoot said "Could
not resolve DNS query" (found 2026-11-08).

Settings > Network > DNS "This device's network" turns it off (no redirect;
the guest's virtual DNS forwards to the device's resolver, `SLIRP_DNS`).

**Verified 2026-11-08:** the 5960 dashboard's connection test asked
`macs.xboxlive.com` and got 49.13.57.101 (log tag `xemu-xboxlive`, `dns:`).

## Status (2026-11-08)

- Works: NAT, DHCP, inbound forward, outbound TCP, guest DNS through the
  built-in resolver, dashboard install, 5960 boots.
- 5960's connection test: **"Unable to reach Xbox Live"**, after DNS succeeded.
  Expected: the console is not registered with Insignia yet (Insignia: register
  with the Setup Assistant before connecting). Next: run the Setup Assistant —
  this sends the console's identity to Insignia, so it waits for the user's
  go-ahead.
- Setup Assistant (2026-11-08): first boot sat on the Xbox logo. It was
  running; its picture wasn't shown. It is nxdk homebrew (SDL + lvgl) that
  draws into the framebuffer with the CPU, so the GPU never makes a surface
  and `nv2a_get_framebuffer_surface()` returns 0, and Android skipped desktop
  xemu's VGA fallback. Fixed: after 500 ms with no GPU frame, `gl_render_frame`
  shows the scanout buffer straight from VRAM (`nv2a_get_scanout()`,
  `scanout_texture()` in `ui/xemu.c`; 15/16/32 bpp, ~10 Hz). Menu verified.
  Applies to any CPU-drawn homebrew. The HUD's FPS counts GPU frames only, so
  it reads 0 there.
- **Registered (2026-11-08):** with both fixes, the Setup Assistant's
  "Register Xbox" reported "Your Xbox was successfully registered".
- Not yet tested: account creation;
  Halo 2 sign-in and matchmaking.

## Open items

- **Crash on the first boot of 5960** (once, right after installing; the next
  two boots were fine): `hw/xbox/nv2a/pfifo.c:536` assertion "Dma value is
  out of range in PFIFO pusher", SIGABRT, ~15 s in. Unreproduced so far.
- **EEPROM backup/restore** so a reinstall keeps the Insignia registration.
- **Setup Assistant automation.** Boxxy could press "Register Xbox" itself
  with injected input (InputRecorder), once its menu layout is known — one tap
  instead of a guided step. Doing the registration without the guest would
  mean reverse engineering Insignia's private registration protocol; not
  planned (their service, their tool, and it can change).
- **Install without xemu's dashboard on the disk**, e.g. a host-side FATX
  writer, for users whose disk has another dashboard.
- **Account detection.** The dashboard stores accounts on the disk; reading
  them (as hakuX does) would let setup tick the last step and show the
  gamertag.

## What could and could not be folded into the app

Asked 2026-11-08: bring the setup into the app rather than separate steps.

- **Done in-app:** connection, DNS (no outside DNS server needed at all),
  dashboard install, Setup Assistant download and launch, sign-up guidance.
- **Could be automated next:** pressing "Register Xbox" (input injection).
- **Must stay with the user:** the email/subscription code (Insignia's
  anti-abuse), supplying the 5960 files (Microsoft's copyright), and the
  dashboard sign-up itself (gamertag, PIN, email typed on the Xbox keyboard;
  injectable in principle but brittle, and the choices are the user's).
- **Not attempted:** replacing the dashboard sign-up or the Setup Assistant
  with Boxxy code. Both speak Insignia's private protocols; reimplementing
  them would break whenever Insignia changes, and isn't ours to do.

## References

- insignia.live/connect — requirements, DNS, 5960 games list link, insigniaDNS.
- Unofficial Insignia guidebook: insignia-1.gitbook.io/unofficial-guide
  (prepare-your-console, insignia-sign-up, register-interest, common-faults).
- Setup Assistant releases: github.com/insignia-live/setup-assistant-release.
- insigniaDNS: github.com/insignia-live/insigniaDNS (MIT).
- hakuX v1.3.0 (github.com/rfandango/hakuX): same approach for the
  dashboard (user's 5960 zip, copied to a folder-based HDD) and Setup
  Assistant (same ISO download).
- xemu networking: xemu.app/docs/networking.
