/*
 * QEMU SMBus Generic Storage Device
 *
 * Copyright (c) 2020 Mike Davis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

#include "qemu/osdep.h"
#include "hw/hw.h"
#include "hw/qdev-properties.h"
#include "hw/i2c/i2c.h"
#include "hw/i2c/smbus_slave.h"
#include "smbus.h"
#include "qapi/error.h"
#include "hw/loader.h"
#include "migration/vmstate.h"

#define TYPE_SMBUS_STORAGE "smbus-storage"
#define SMBUS_STORAGE(obj) OBJECT_CHECK(SMBusStorageDevice, (obj), \
    TYPE_SMBUS_STORAGE)

//#define DEBUG
#ifdef DEBUG
# define DPRINTF(format, ...) printf(format, ## __VA_ARGS__)
#else
# define DPRINTF(format, ...) do { } while (0)
#endif

typedef struct SMBusStorageDevice {
    SMBusDevice smbusdev;
    char* file;
    uint8_t *data;
    uint32_t size;
    uint8_t addr;
    uint32_t offset;
    bool persist;
} SMBusStorageDevice;

/* The Xbox's EEPROM (address 0x54), for the host to read and change its
 * settings while the machine runs: see xbox_eeprom_data(). */
static SMBusStorageDevice *g_xbox_eeprom;

static int smbus_storage_persist(SMBusStorageDevice *s)
{
    if (!s->file || !s->persist) {
        return 0;
    }
    int fd = qemu_open(s->file, O_WRONLY | O_BINARY, NULL);
    if (fd < 0) {
        DPRINTF("%s: file '%s' could not be opened\n", __func__, s->file);
        return -1;
    }
    int wc = write(fd, s->data, s->size);
    close(fd);
    if (wc != s->size) {
        DPRINTF("%s: file '%s' write failure\n", __func__, s->file);
        return -1;
    }
    return 0;
}

/* The running Xbox's EEPROM contents, or NULL before the machine exists.
 * Change it only with the BQL held, then call xbox_eeprom_persist(). */
uint8_t *xbox_eeprom_data(void)
{
    return g_xbox_eeprom ? g_xbox_eeprom->data : NULL;
}

void xbox_eeprom_persist(void)
{
    if (g_xbox_eeprom) {
        smbus_storage_persist(g_xbox_eeprom);
    }
}

static void smbus_storage_realize(DeviceState *dev, Error **errp)
{
    SMBusStorageDevice *s = SMBUS_STORAGE(dev);
    qdev_prop_set_uint8(dev, "address", s->addr);
    if (s->addr == 0x54) {
        g_xbox_eeprom = s;
    }
    s->data = g_malloc0(s->size);
    s->offset = 0;

    if (s->file) {
        int size = get_image_size(s->file, NULL);
        if (size != s->size) {
            error_setg(errp, "%s: file '%s' size of %d, expected %d\n",
                __func__, s->file, size, s->size);
            return;
        }

        int fd = qemu_open(s->file, O_RDONLY | O_BINARY, NULL);
        if (fd < 0) {
            error_setg(errp, "%s: file '%s' could not be opened\n",
                __func__, s->file);
            return;
        }

        int rc = read(fd, s->data, s->size);
        if (rc != s->size) {
            error_setg(errp, "%s: file '%s' read failure\n", __func__, s->file);
            close(fd);
            return;
        }
        close(fd);
    } else {
        error_setg(errp, "%s: file unspecified\n", __func__);
    }
}

static int smbus_storage_write_data(SMBusDevice *dev, uint8_t *buf, uint8_t len)
{
    SMBusStorageDevice *s = SMBUS_STORAGE(dev);

    DPRINTF("%s: addr=0x%02x cmd=0x%02x val=0x%02x\n",
        __func__, s->addr, buf[0], buf[1]);

    /* len is guaranteed to be > 0 */
    s->offset = buf[0];
    buf++;
    len--;

    bool changed = false;
    for (; len > 0; len--) {
        changed = true;
        s->data[s->offset] = *buf++;
        DPRINTF("%s: addr=0x%02x off=0x%02x, data=0x%02x\n",
            __func__, s->addr, s->offset, s->data[s->offset]);
        s->offset = (s->offset + 1) % s->size;
    }

    if (changed && smbus_storage_persist(s) < 0) {
        return -1;
    }

    return 0;
}

static uint8_t smbus_storage_receive_byte(SMBusDevice *dev)
{
    SMBusStorageDevice *s = SMBUS_STORAGE(dev);

    uint8_t val = s->data[s->offset];
    DPRINTF("%s: addr=0x%02x off=0x%02x val=0x%02x\n",
        __func__, s->addr, s->offset, val);
    s->offset = (s->offset + 1) % s->size;

    return val;
}

static const VMStateDescription vmstate_smbus_storage = {
    .name = "smbus-storage",
    .version_id = 1,
    .minimum_version_id = 1,
    .fields      = (VMStateField[]) {
        VMSTATE_SMBUS_DEVICE(smbusdev, SMBusStorageDevice),
        VMSTATE_VBUFFER_UINT32(data, SMBusStorageDevice, 1, NULL, size),
        VMSTATE_UINT32(offset, SMBusStorageDevice),
        VMSTATE_END_OF_LIST()
    }
};

// default xbox eeprom with persistence
static const Property smbus_storage_props[] = {
    DEFINE_PROP_UINT8("addr", SMBusStorageDevice, addr, 0x54),
    DEFINE_PROP_UINT32("size", SMBusStorageDevice, size, 256),
    DEFINE_PROP_BOOL("persist", SMBusStorageDevice, persist, true),
    DEFINE_PROP_STRING("file", SMBusStorageDevice, file),
};

static void smbus_storage_class_init(ObjectClass *klass, const void *data)
{
    DeviceClass *dc = DEVICE_CLASS(klass);
    SMBusDeviceClass *sc = SMBUS_DEVICE_CLASS(klass);

    dc->vmsd = &vmstate_smbus_storage;
    dc->realize = smbus_storage_realize;
    sc->receive_byte = smbus_storage_receive_byte;
    sc->write_data = smbus_storage_write_data;
    device_class_set_props(dc, smbus_storage_props);
}

static TypeInfo smbus_storage_info = {
    .name = TYPE_SMBUS_STORAGE,
    .parent = TYPE_SMBUS_DEVICE,
    .instance_size = sizeof(SMBusStorageDevice),
    .class_init = smbus_storage_class_init,
};

static void smbus_storage_register_devices(void)
{
    type_register_static(&smbus_storage_info);
}

type_init(smbus_storage_register_devices)
