package dev.ichinomiya.ninebotenhance.platform;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Build;

/** GATT writes on both API generations: the value-carrying calls of Android 13, the set-then-write pair before it. */
public final class Gatt {
    @SuppressWarnings("deprecation")
    public static int write(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int type) {
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeCharacteristic(characteristic, value, type);
        characteristic.setWriteType(type); characteristic.setValue(value);
        return gatt.writeCharacteristic(characteristic) ? BluetoothStatusCodes.SUCCESS : BluetoothStatusCodes.ERROR_UNKNOWN;
    }
    @SuppressWarnings("deprecation")
    public static int writeDescriptor(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeDescriptor(descriptor, value);
        descriptor.setValue(value);
        return gatt.writeDescriptor(descriptor) ? BluetoothStatusCodes.SUCCESS : BluetoothStatusCodes.ERROR_UNKNOWN;
    }
    /** Before Android 13 only the two-argument notification callback fires; from 13 both do and the three-argument one carries the bytes. */
    public static boolean legacyCallbacks() { return Build.VERSION.SDK_INT < 33; }
    private Gatt() {}
}
