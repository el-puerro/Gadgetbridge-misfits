package nodomain.freeyourgadget.gadgetbridge.devices.misfits;

import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.devices.misfits.MisfitsDeviceSupport;

public class MisfitsCoordinator extends AbstractBLEDeviceCoordinator {

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        ParcelUuid cts = new ParcelUuid(GattService.UUID_SERVICE_CURRENT_TIME);
        return Collections.singletonList(
            new ScanFilter.Builder().setServiceUuid(cts).build());
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("misfits");
    }

    @Override
    public String getManufacturer() {
        return "Red Lucy Engineering";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_misfits;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    protected void deleteDevice(@NonNull GBDevice gbDevice,
                                @NonNull Device device,
                                @NonNull DaoSession session) {
    }

    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return MisfitsDeviceSupport.class;
    }

    @Override
    public DeviceKind getDeviceKind(GBDevice device) {
        return DeviceKind.WATCH;
    }
}
