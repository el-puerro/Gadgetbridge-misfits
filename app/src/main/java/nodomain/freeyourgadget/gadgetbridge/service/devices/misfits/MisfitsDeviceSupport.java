package nodomain.freeyourgadget.gadgetbridge.service.devices.misfits;

import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Calendar;
import java.util.GregorianCalendar;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;

public class MisfitsDeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MisfitsDeviceSupport.class);

    public MisfitsDeviceSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_CURRENT_TIME);
        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));
        writeCurrentTime(builder);
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        return builder;
    }

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder builder = performInitialized("set time");
            writeCurrentTime(builder);
            builder.queue();
        } catch (IOException e) {
            LOG.error("failed to set time", e);
        }
    }

    private void writeCurrentTime(TransactionBuilder builder) {
        BluetoothGattCharacteristic chr = getCharacteristic(GattCharacteristic.UUID_CHARACTERISTIC_CURRENT_TIME);
        if (chr == null) {
            LOG.warn("current time characteristic not found");
            return;
        }
        byte[] payload = buildCurrentTimePayload();
        LOG.info("writing CTS payload, {} bytes", payload.length);
        builder.write(chr, payload);
    }

    private byte[] buildCurrentTimePayload() {
        GregorianCalendar c = new GregorianCalendar();

        int year = c.get(Calendar.YEAR);
        int dayOfWeek = c.get(Calendar.DAY_OF_WEEK) - 1;
        if (dayOfWeek == 0) {
            dayOfWeek = 7;
        }
        int fractions256 = c.get(Calendar.MILLISECOND) * 256 / 1000;

        return new byte[]{
            (byte) (year & 0xFF),
            (byte) ((year >> 8) & 0xFF),
            (byte) (c.get(Calendar.MONTH) + 1),
            (byte) c.get(Calendar.DAY_OF_MONTH),
            (byte) c.get(Calendar.HOUR_OF_DAY),
            (byte) c.get(Calendar.MINUTE),
            (byte) c.get(Calendar.SECOND),
            (byte) dayOfWeek,
            (byte) fractions256,
            (byte) 0x01,
        };
    }
}
