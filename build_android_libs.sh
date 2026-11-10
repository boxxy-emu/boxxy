#!/bin/bash
# Build the Meson libraries CMakeLists.txt links, and assemble the archives
# the Android link needs.  Run by rebuild_meson.sh after `meson setup`; then
# fixup_archives.sh --no-ninja removes the duplicate objects.  See CLAUDE.md.
set -uo pipefail
cd "$(dirname "$0")"
source android/tools/ndk-env.sh

# -k 0: keep going past unrelated failures (vfio-user, 9pfs have Android/Bionic
# incompatibilities but are not needed for Xbox emulation).
# After the build, libsystem.a and libcommon.a are created manually from
# whatever objects compiled successfully. CMakeLists.txt links all Meson
# libraries with lazy loading, so objects with unresolvable dependencies are
# never pulled in unless their symbols are actually referenced by Xbox code.
ninja -C build -k 0 \
    libqemuutil.a \
    libcommon.a \
    libqom.a \
    libsystem.a \
    libblock.a \
    libblockdev.a \
    libchardev.a \
    libhwcore.a \
    libio.a \
    libauthz.a \
    libmigration.a \
    libqmp.a \
    libcrypto.a \
    libevent-loop-base.a \
    libqemu-i386-softmmu.a \
    data/libpdata.fa \
    subprojects/imgui/libimgui.a \
    subprojects/implot/libimplot.a \
    "subprojects/xxHash-0.8.3/libxxhash.a" \
    subprojects/tomlplusplus/src/libtomlplusplus.a \
    subprojects/volk/libvolk.a \
    subprojects/glslang/libglslang.a \
    subprojects/glslang/libMachineIndependent.a \
    subprojects/glslang/libGenericCodeGen.a \
    subprojects/glslang/libOSDependent.a \
    subprojects/glslang/libglslang_default_resource_limits.a \
    "subprojects/SPIRV-Reflect/libspirv_reflect_static.a" \
    subprojects/berkeley-softfloat-3/libsoftfloat.a \
    subprojects/nv2a_vsh_cpu/libnv2a_vsh_emulator.a \
    subprojects/nv2a_vsh_cpu/libnv2a_vsh_disassembler.a \
    subprojects/nv2a_vsh_cpu/libnv2a_vsh_cpu.a \
    hw/xbox/nv2a/pgraph/thirdparty/liblibgloffscreen.a \
    hw/xbox/mcpx/apu/dsp/libdsp.a \
    hw/xbox/mcpx/apu/dsp/interp/libdsp_cpu_interp.a \
    thirdparty/libvma.a \
    ui/thirdparty/libstb_image.a \
    ui/thirdparty/libfpng.a \
    ui/thirdparty/libfatx.a \
    subprojects/slirp/libslirp.so

# ninja skips the archive step for libsystem.a and libcommon.a when any input
# object fails (even with -k 0). Recreate them from whatever objects compiled
# successfully. Meson libraries are linked lazily (CMakeLists.txt does NOT use
# --whole-archive), so most non-Xbox objects are harmless in the archive since
# nothing ever references their symbols.
#
# The one exception is vfio: backends/iommufd.c fails to compile on Android
# (missing Linux iommu headers in the NDK), so any object that compiled but
# depends on iommufd symbols must be excluded. These objects are pulled in
# transitively through the migration/CPR system (which Xbox does use), so they
# cannot be left in the archive the way ARM/OMAP/SCSI objects can.
#
# Excluded from libsystem.a (--whole-archive requires all symbols be resolvable):
#   hw_vfio_* / hw_vfio-user_*: need backends/iommufd.c + libvfio-user (NDK missing Linux iommu headers)
#     Exception: hw_vfio_iommufd-stubs.c.o is added back below (zero external deps, provides
#     vmstate_cpr_vfio_devices needed by migration_cpr.c.o)
#   migration_vfio.c.o: references hw_vfio symbols
#   migration_cpr.c.o: NOT excluded any more — its only vfio dep (vmstate_cpr_vfio_devices) is
#     satisfied by hw_vfio_iommufd-stubs.c.o added back below; cpr_state_load is needed by vl.c
#   hw_virtio_*: CONFIG_VIRTIO not set; virtio.c core never compiled
#     Exceptions: virtio-stub, virtio-hmp-cmds, virtio-md-stubs, vhost-stub are added back below
#     (clean deps only, provide qmp/hmp virtio query stubs needed by monitor objects)
#   hw_char_virtio-console.c.o: references virtio_serial_open (CONFIG_VIRTIO not set)
#   hw_input_virtio-input*.c.o: references virtio core (hw_input_ prefix)
#   hw_vmapple_*: Apple VM virtio-blk, references virtio_blk_req_complete
#   hw_s390x_*: s390x machine objects (virtio-ccw-gpu, have_virtio_ccw)
#   hw_char_sclpconsole*.c.o: references sclp_service_interrupt (s390x-only)
#   semihosting_arm-compat-semi.c.o: references common_semi_arg (ARM semihosting, not compiled for i386)
#   hw_misc_imx*.c.o: i.MX6/i.MX7 SRC/CCM — references arm_set_cpu_off/on (ARM target)
#   hw_misc_iotkit*.c.o: ARM IoTKit/M-profile sysctl, references arm_* (ARM target)
#   hw_misc_xlnx*.c.o: Xilinx Zynq/Versal, references arm_* (ARM target)
#   hw_*_omap*.c.o: ARM OMAP peripherals, reference omap_badwidth_read/write16
#   hw_intc_arm_gic*.c.o: ARM GIC/GICv2/GICv3 interrupt controllers; gic_common and gic declare
#     the arm-linux-boot-if interface (registered only by hw/arm/boot.c, not compiled for i386)
#   hw_intc_openpic.c.o: PowerPC OpenPIC, references ppce500_set_mpic_proxy (PowerPC target)
#   hw_scsi_virtio-scsi-dataplane.c.o: references virtio_bus_set_host_notifier (CONFIG_VIRTIO not set)
#   hw_scsi_vhost-user-scsi.c.o: references vhost_scsi_common_* (CONFIG_VHOST not set)
#   hw_hyperv_*.c.o: Hyper-V test device, references hyperv_sint_route_new etc. (not compiled for Xbox)
#   hw_remote_*.c.o: multi-process QEMU, references mpqemu_*/proxy_memory_listener_* (not built)
#   hw_core_guest-loader.c.o: references qemu_fdt_* (device tree, not used by Xbox)
#   ui_console-vc-stubs.c.o: stub that wins alphabetically over ui_console-vc.c.o (the real impl
#     that registers chardev-vc via qemu_console_early_init); stub does nothing → chardev 'vc' lookup
#     fails at startup with "not a valid char driver name"
make_archive_excluding() {
    local archive="$1"; shift
    local srcdir="$1"; shift
    local -a exclude_patterns=("$@")
    local -a objs=()
    local excluded=0
    for obj in "$srcdir"/*.o; do
        local name; name=$(basename "$obj")
        local skip=0
        for pat in "${exclude_patterns[@]}"; do
            case "$name" in $pat) skip=1; break ;; esac
        done
        if [ "$skip" -eq 1 ]; then
            (( excluded++ )) || true
        else
            objs+=("$obj")
        fi
    done
    rm -f "$archive"
    "$LLVM_AR" rcs "$archive" "${objs[@]}"
    local total; total=$(ls "$srcdir"/*.o | wc -l | tr -d ' ')
    echo "Created $(basename "$archive") (${#objs[@]} objects, excluded $excluded of $total)"
}

make_archive_excluding build/libsystem.a build/libsystem.a.p \
    "hw_vfio_*" "hw_vfio-user_*" \
    "migration_vfio.c.o" \
    "hw_virtio_*" \
    "hw_char_virtio-console.c.o" \
    "hw_input_virtio-input*.c.o" \
    "hw_vmapple_*" \
    "hw_s390x_*" \
    "hw_char_sclpconsole*.c.o" \
    "semihosting_arm-compat-semi.c.o" \
    "hw_misc_imx*.c.o" \
    "hw_misc_iotkit*.c.o" \
    "hw_misc_xlnx*.c.o" \
    "hw_*_omap*.c.o" \
    "hw_intc_arm_gic*.c.o" \
    "hw_intc_openpic.c.o" \
    "hw_scsi_virtio-scsi-dataplane.c.o" \
    "hw_scsi_vhost-user-scsi.c.o" \
    "hw_hyperv_*.c.o" \
    "hw_remote_*.c.o" \
    "hw_core_guest-loader.c.o" \
    "hw_audio_virtio-snd*.c.o" \
    "hw_block_xen-block.c.o" \
    "hw_timer_imx*.c.o" \
    "hw_acpi_ich9*.c.o" \
    "fsdev_qemu-fsdev.c.o" \
    "ui_xemu*.c.o" \
    "ui_console-vc-stubs.c.o"

# Add back specific stub objects that were caught by the broad hw_vfio_* and
# hw_virtio_* / hw_hyperv_* exclusion patterns but are safe to include and
# satisfy undefined symbols referenced by objects we DO include.
#
#   hw_vfio_iommufd-stubs.c.o:    zero external deps; provides vmstate_cpr_vfio_devices
#                                  needed by migration_cpr.c.o
#   migration_cpr.c.o:            provides cpr_state_load/cpr_*_fd used by system/vl.c;
#                                  its only vfio dep (vmstate_cpr_vfio_devices) is satisfied above
#   hw_virtio_virtio-stub.c.o:    provides qmp_x_query_virtio* (no-ops, CONFIG_VIRTIO not set)
#   hw_virtio_virtio-hmp-cmds.c.o: provides hmp_virtio_* (monitor commands, needs virtio-stub above)
#   hw_virtio_virtio-md-stubs.c.o: provides virtio_md_pci_* hotplug stubs
#   hw_virtio_vhost-stub.c.o:     provides hmp_vhost_queue_status and similar vhost stubs
#   hw_hyperv_hv-balloon-stub.c.o: provides qmp_query_hv_balloon_status_report (no-op stub)
"$LLVM_AR" q build/libsystem.a \
    build/libsystem.a.p/hw_vfio_iommufd-stubs.c.o \
    build/libsystem.a.p/hw_virtio_virtio-stub.c.o \
    build/libsystem.a.p/hw_virtio_virtio-hmp-cmds.c.o \
    build/libsystem.a.p/hw_virtio_virtio-md-stubs.c.o \
    build/libsystem.a.p/hw_virtio_vhost-stub.c.o \
    build/libsystem.a.p/hw_hyperv_hv-balloon-stub.c.o
echo "Added back 6 stub objects to libsystem.a"

# libcommon.a — exclude objects whose compile-time dependencies are missing:
#   disas_xtensa.c.o:            references Xtensa ISA library (libisa), not built for our config
#   disas_hexagon.c.o:           references disassemble_hexagon from a Hexagon-specific library
#   hw_mips_loongson3_bootp.c.o: references virt_memmap (MIPS target-specific, not compiled)
make_archive_excluding build/libcommon.a build/libcommon.a.p \
    "disas_xtensa.c.o" \
    "disas_hexagon.c.o" \
    "hw_mips_loongson3_bootp.c.o"

# Rebuild libqemu-i386-softmmu.a from only its own objects.
# Meson's generated archive is a "fat archive": it bundles objects from
# libsystem.a.p and libcommon.a.p alongside the actual i386-softmmu objects.
# When CMakeLists.txt links both libsystem.a/libcommon.a AND this archive
# with --whole-archive, every shared object is pulled in twice, causing every
# type_init() constructor to run twice → "Registering 'cpu' which already
# exists" (and similar) at startup.
#
# The fix: keep only the 152 objects from libqemu-i386-softmmu.a.p/ itself.
# libsystem.a and libcommon.a (our filtered versions) supply the rest.
rm -f build/libqemu-i386-softmmu.a
"$LLVM_AR" rcs build/libqemu-i386-softmmu.a build/libqemu-i386-softmmu.a.p/*.o
echo "Rebuilt libqemu-i386-softmmu.a ($(ls build/libqemu-i386-softmmu.a.p/*.o | wc -l | tr -d ' ') objects, own .p dir only)"

# Verify the critical archives that CMakeLists.txt links directly
CRITICAL=(
    build/libsystem.a
    build/libqemuutil.a
    build/libqemu-i386-softmmu.a
    build/data/libpdata.fa
    build/subprojects/slirp/libslirp.so
)
MISSING=0
for f in "${CRITICAL[@]}"; do
    if [ ! -f "$f" ]; then
        echo "ERROR: critical artifact missing: $f"
        MISSING=1
    fi
done

if [ $MISSING -eq 1 ]; then
    echo "Critical artifacts are missing — check the ninja output above for errors."
    exit 1
fi

bash ./fixup_archives.sh --no-ninja
echo "Build complete. All critical artifacts present."
